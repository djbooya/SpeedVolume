package com.djbooya.speedvolume

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlin.math.roundToInt

/**
 * Foreground service that reads GPS speed and nudges the media volume up
 * in up to two configurable tiers, reverting each tier's boost once speed
 * drops back below its threshold.
 *
 * Tracks volume baseline (the volume without any boosts) to preserve manual
 * adjustments made by the user after a boost is applied.
 */
class SpeedVolumeService : Service() {

    private lateinit var locationManager: LocationManager
    private lateinit var audioManager: AudioManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var settings: AppSettings

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private var tier1AboveSince: Long? = null
    private var tier1Engaged: Boolean = false
    private var tier2AboveSince: Long? = null
    private var tier2Engaged: Boolean = false

    private var currentTier1Boost: Int = 0
    private var currentTier2Boost: Int = 0

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handler.post { handleLocation(location) }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        DebugLog.d("SpeedVolumeService", "Service created")

        val defaultUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            DebugLog.e("SpeedVolumeService", "UNCAUGHT EXCEPTION in thread ${thread.name}: ${throwable.message}")
            DebugLog.e("SpeedVolumeService", throwable.stackTraceToString())
            android.util.Log.e("SpeedVolume", "CRASH: ${throwable.message}", throwable)
            defaultUncaughtHandler?.uncaughtException(thread, throwable)
        }

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        settingsRepository = SettingsRepository(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLog.d("SpeedVolumeService", "=== SERVICE STARTED v1.8 ===")
        android.util.Log.d("SpeedVolume", "=== SERVICE STARTED v1.8 ===")
        DebugLog.d("SpeedVolumeService", "onStartCommand called")
        android.util.Log.d("SpeedVolume", "onStartCommand called")
        settings = settingsRepository.load()
        DebugLog.d("SpeedVolumeService", "Settings loaded: enabled=${settings.masterEnabled}")
        DebugLog.d("SpeedVolumeService", "CONFIG: speedUnit=${settings.speedUnit.name}, startOnBoot=${settings.startOnBoot}")
        DebugLog.d("SpeedVolumeService", "CONFIG: Tier1 enabled=${settings.tier1.enabled}, threshold=${settings.tier1.speedThreshold}${settings.speedUnit.name}, boost=+${settings.tier1.volumeIncreaseSteps}steps, dwell=${settings.tier1.dwellSeconds}s")
        DebugLog.d("SpeedVolumeService", "CONFIG: Tier2 enabled=${settings.tier2.enabled}, threshold=${settings.tier2.speedThreshold}${settings.speedUnit.name}, boost=+${settings.tier2.volumeIncreaseSteps}steps, dwell=${settings.tier2.dwellSeconds}s")
        android.util.Log.d("SpeedVolume", "CONFIG: T1=${settings.tier1.speedThreshold}@${settings.tier1.dwellSeconds}s+${settings.tier1.volumeIncreaseSteps}, T2=${settings.tier2.speedThreshold}@${settings.tier2.dwellSeconds}s+${settings.tier2.volumeIncreaseSteps}")

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            DebugLog.e("SpeedVolumeService", "Location permission NOT granted - stopping service")
            android.util.Log.e("SpeedVolume", "Location permission NOT granted")
            ServiceStatus.update { it.copy(running = false) }
            stopSelf()
            return START_NOT_STICKY
        }

        DebugLog.d("SpeedVolumeService", "Location permission granted - starting service")
        android.util.Log.d("SpeedVolume", "Location permission granted")
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val batteryUnrestricted = powerManager.isIgnoringBatteryOptimizations(packageName)
        val exactAlarmAllowed = ServiceRestartAlarm.canScheduleExact(this)
        DebugLog.d("SpeedVolumeService", "Battery optimization ignored=$batteryUnrestricted, exact alarms allowed=$exactAlarmAllowed")
        startForeground(NOTIFICATION_ID, buildNotification(null))
        ServiceStatus.update {
            it.copy(running = true, speedUnit = settings.speedUnit, hasFix = false)
        }

        acquireWakeLock(powerManager)
        startLocationUpdates()
        scheduleLocationUpdateCheck()
        scheduleHeartbeatLog()
        ServiceRestartAlarm.scheduleRestartAlarm(this)
        DebugLog.d("SpeedVolumeService", "Service started successfully")
        android.util.Log.d("SpeedVolume", "Service started - listening for GPS")

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugLog.d("SpeedVolumeService", "=== SERVICE DESTROYED ===")
        DebugLog.d("SpeedVolumeService", "Process being terminated - service lifecycle ending")
        android.util.Log.d("SpeedVolume", "=== SERVICE onDestroy called ===")

        handler.removeCallbacksAndMessages(null)
        ServiceRestartAlarm.cancelRestartAlarm(this)
        releaseWakeLock()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            locationManager.removeUpdates(locationListener)
            DebugLog.d("SpeedVolumeService", "Location updates stopped")
        }
        revertAllBoosts()
        ServiceStatus.update { it.copy(running = false, tier1Engaged = false, tier2Engaged = false) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val levelName = when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "CRITICAL"
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "LOW"
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "MODERATE"
            else -> "UNKNOWN($level)"
        }
        DebugLog.d("SpeedVolumeService", "Memory pressure detected: TRIM_MEMORY_$levelName")
        android.util.Log.w("SpeedVolume", "MEMORY PRESSURE: $levelName")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        DebugLog.d("SpeedVolumeService", "CRITICAL: onLowMemory() called - system may kill service")
        android.util.Log.e("SpeedVolume", "CRITICAL LOW MEMORY - Service may be terminated")
    }

    /**
     * Some head units clear the "recent tasks" list on their own (or the user swipes the
     * app away), and many OEM skins kill the whole process right after this callback
     * returns - restarting the service inline here often gets killed along with it.
     * Instead, arm a short exact alarm so the OS restarts us a few seconds later, once
     * this process is already gone.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        DebugLog.d("SpeedVolumeService", "=== onTaskRemoved: task cleared, arming quick-restart alarm ===")
        android.util.Log.d("SpeedVolume", "=== TASK REMOVED ===")
        ServiceRestartAlarm.scheduleRestartAlarm(this, delayMs = 5000L)
    }

    override fun onBind(intent: Intent?) = null

    /**
     * Holds the CPU awake for as long as the service runs, so Doze/idle sleep never
     * suspends GPS updates or the restart alarm in the first place - the same technique
     * LlamaLab's Automate app uses (its "Device keep awake" block) to stay reliable in
     * the background. The head unit is on constant power, so the battery cost is moot.
     */
    private fun acquireWakeLock(powerManager: PowerManager) {
        if (wakeLock?.isHeld == true) return
        val lock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SpeedVolume:LocationWakeLock"
        )
        lock.setReferenceCounted(false)
        lock.acquire()
        wakeLock = lock
        DebugLog.d("SpeedVolumeService", "Wake lock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                DebugLog.d("SpeedVolumeService", "Wake lock released")
            }
        }
        wakeLock = null
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            DebugLog.e("SpeedVolumeService", "startLocationUpdates: permission denied")
            return
        }

        try {
            val providers = locationManager.getProviders(true)
            DebugLog.d("SpeedVolumeService", "Available providers: $providers")
            if (LocationManager.GPS_PROVIDER in providers) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_UPDATE_MS,
                    0f,
                    locationListener
                )
                DebugLog.d("SpeedVolumeService", "GPS provider location updates requested")
            }
            if (LocationManager.NETWORK_PROVIDER in providers) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MIN_UPDATE_MS,
                    0f,
                    locationListener
                )
                DebugLog.d("SpeedVolumeService", "Network provider location updates requested")
            }
        } catch (e: Exception) {
            DebugLog.e("SpeedVolumeService", "Failed to start location updates", e)
        }
    }

    private fun scheduleLocationUpdateCheck() {
        handler.postDelayed({
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    locationManager.removeUpdates(locationListener)
                    startLocationUpdates()
                } catch (e: Exception) {
                    // Ignore; will retry on next check
                }
            }
            scheduleLocationUpdateCheck()
        }, LOCATION_UPDATE_RESTART_MS)
    }

    private fun scheduleHeartbeatLog() {
        handler.postDelayed({
            DebugLog.d("SpeedVolumeService", "HEARTBEAT: Service alive, tier1=${tier1Engaged}, tier2=${tier2Engaged}")
            android.util.Log.d("SpeedVolume", "HEARTBEAT: Service process alive")
            scheduleHeartbeatLog()
        }, HEARTBEAT_LOG_MS)
    }

    private fun handleLocation(location: Location) {
        if (!location.hasSpeed()) {
            DebugLog.d("SpeedVolumeService", "Location received but no speed data")
            android.util.Log.d("SpeedVolume", "Location: no speed data")
            return
        }

        val speedMps = location.speed
        val speedInUnit = when (settings.speedUnit) {
            SpeedUnit.KMH -> speedMps * 3.6
            SpeedUnit.MPH -> speedMps * 2.23694
        }.roundToInt()
        DebugLog.d("SpeedVolumeService", "Speed: $speedInUnit ${settings.speedUnit.name}")
        android.util.Log.d("SpeedVolume", "Speed: $speedInUnit ${settings.speedUnit.name} | Tier1: engaged=$tier1Engaged, Tier2: engaged=$tier2Engaged")

        val now = SystemClock.elapsedRealtime()

        if (settings.tier1.enabled) {
            val result = evaluateTier(settings.tier1, speedInUnit, now, tier1AboveSince, tier1Engaged)
            val stateChanged = tier1Engaged != result.engaged
            tier1AboveSince = result.aboveSince
            tier1Engaged = result.engaged
            if (stateChanged) {
                DebugLog.d("SpeedVolumeService", "Tier 1: ${if (result.engaged) "ENGAGED (+${settings.tier1.volumeIncreaseSteps})" else "DISENGAGED"}")
                android.util.Log.d("SpeedVolume", "Tier 1: ${if (result.engaged) "ENGAGED" else "DISENGAGED"}")
            }
        } else {
            tier1Engaged = false
        }

        if (settings.tier2.enabled) {
            val result = evaluateTier(settings.tier2, speedInUnit, now, tier2AboveSince, tier2Engaged)
            val stateChanged = tier2Engaged != result.engaged
            tier2AboveSince = result.aboveSince
            tier2Engaged = result.engaged
            if (stateChanged) {
                DebugLog.d("SpeedVolumeService", "Tier 2: ${if (result.engaged) "ENGAGED (+${settings.tier2.volumeIncreaseSteps})" else "DISENGAGED"}")
                android.util.Log.d("SpeedVolume", "Tier 2: ${if (result.engaged) "ENGAGED" else "DISENGAGED"}")
            }
        } else {
            tier2Engaged = false
        }

        applyVolumeChanges()
        ServiceStatus.update {
            it.copy(
                currentSpeed = speedInUnit,
                hasFix = true,
                tier1Engaged = tier1Engaged,
                tier2Engaged = tier2Engaged
            )
        }
        updateNotification(speedInUnit)
    }

    private data class TierEvalResult(val aboveSince: Long?, val engaged: Boolean)

    private fun evaluateTier(
        tier: TierConfig,
        speedInUnit: Int,
        now: Long,
        aboveSince: Long?,
        currentlyEngaged: Boolean
    ): TierEvalResult {
        if (speedInUnit >= tier.speedThreshold) {
            val since = aboveSince ?: now
            if (!currentlyEngaged && now - since >= tier.dwellSeconds * 1000L) {
                return TierEvalResult(since, true)
            }
            return TierEvalResult(since, currentlyEngaged)
        } else {
            return TierEvalResult(null, false)
        }
    }

    private fun applyVolumeChanges() {
        val newTier1Boost = if (tier1Engaged) settings.tier1.volumeIncreaseSteps else 0
        val newTier2Boost = if (tier2Engaged) settings.tier2.volumeIncreaseSteps else 0

        val tier1Delta = newTier1Boost - currentTier1Boost
        val tier2Delta = newTier2Boost - currentTier2Boost

        if (tier1Delta != 0) {
            adjustVolume(tier1Delta)
            DebugLog.d("SpeedVolumeService", "Tier 1 volume delta: $tier1Delta")
            android.util.Log.d("SpeedVolume", "Volume adjustment: tier1 delta=$tier1Delta")
            currentTier1Boost = newTier1Boost
        }

        if (tier2Delta != 0) {
            adjustVolume(tier2Delta)
            DebugLog.d("SpeedVolumeService", "Tier 2 volume delta: $tier2Delta")
            android.util.Log.d("SpeedVolume", "Volume adjustment: tier2 delta=$tier2Delta")
            currentTier2Boost = newTier2Boost
        }
    }

    private fun adjustVolume(deltaSteps: Int) {
        if (deltaSteps == 0) return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val min = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (current + deltaSteps).coerceIn(min, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        DebugLog.d("SpeedVolumeService", "Volume: $current + $deltaSteps = $target")
        android.util.Log.d("SpeedVolume", "VOLUME CHANGE: $current -> $target (delta=$deltaSteps)")
    }

    private fun revertAllBoosts() {
        if (currentTier1Boost > 0) {
            adjustVolume(-currentTier1Boost)
            currentTier1Boost = 0
        }
        if (currentTier2Boost > 0) {
            adjustVolume(-currentTier2Boost)
            currentTier2Boost = 0
        }
    }

    private fun buildNotification(speedInUnit: Int?): Notification {
        val channelId = ensureChannel()
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val unitLabel = if (settings.speedUnit == SpeedUnit.KMH) "km/h" else "mph"
        val text = if (speedInUnit != null) {
            "$speedInUnit $unitLabel" + if (tier1Engaged || tier2Engaged) " · boosted" else ""
        } else {
            getString(R.string.status_waiting)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_speed)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(speedInUnit: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(speedInUnit))
    }

    private fun ensureChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        return CHANNEL_ID
    }

    companion object {
        private const val CHANNEL_ID = "speed_volume_service"
        private const val NOTIFICATION_ID = 1001
        private const val MIN_UPDATE_MS = 1000L
        private const val LOCATION_UPDATE_RESTART_MS = 60000L
        private const val HEARTBEAT_LOG_MS = 120000L
    }
}
