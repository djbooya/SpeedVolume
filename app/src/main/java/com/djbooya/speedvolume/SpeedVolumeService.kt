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

    /**
     * Per-tier runtime state. Deliberately not persisted: a fresh process starts every
     * tier disarmed with nothing applied, which is what makes a restart mid-drive safe.
     */
    private class TierState {
        /**
         * A tier can only engage after we have seen the vehicle *below* its threshold.
         * Starting the service while already above a threshold leaves that tier disarmed,
         * so the boost waits for a real crossing rather than firing on the first fix.
         */
        var armed: Boolean = false
        var aboveSince: Long? = null
        var engaged: Boolean = false

        /**
         * Volume steps this tier has actually pushed onto the stream - not what it wanted
         * to push. If a boost was clamped at the max volume it was never applied, so it
         * must never be subtracted back off.
         */
        var appliedBoost: Int = 0
    }

    private val tierStates = List(AppSettings.TIER_COUNT) { TierState() }

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
        DebugLog.d("SpeedVolumeService", "=== SERVICE STARTED v2.0 ===")
        android.util.Log.d("SpeedVolume", "=== SERVICE STARTED v2.0 ===")
        DebugLog.d("SpeedVolumeService", "onStartCommand called")
        android.util.Log.d("SpeedVolume", "onStartCommand called")
        settings = settingsRepository.load()
        DebugLog.d("SpeedVolumeService", "Settings loaded: enabled=${settings.masterEnabled}")
        DebugLog.d("SpeedVolumeService", "CONFIG: speedUnit=${settings.speedUnit.name}, startOnBoot=${settings.startOnBoot}")
        settings.tiers.forEachIndexed { index, tier ->
            DebugLog.d(
                "SpeedVolumeService",
                "CONFIG: Tier${index + 1} enabled=${tier.enabled}, threshold=${tier.speedThreshold}${settings.speedUnit.name}, boost=+${tier.volumeIncreaseSteps}steps, dwell=${tier.dwellSeconds}s"
            )
        }
        android.util.Log.d(
            "SpeedVolume",
            "CONFIG: " + settings.tiers.mapIndexed { index, tier ->
                "T${index + 1}=${tier.speedThreshold}@${tier.dwellSeconds}s+${tier.volumeIncreaseSteps}${if (tier.enabled) "" else "(off)"}"
            }.joinToString(", ")
        )

        // The service is exported so Automate can restart it, which means a start can
        // arrive at any time - including after the user has switched the app off. Honour
        // the master switch rather than trusting whoever started us.
        if (!settings.masterEnabled) {
            DebugLog.d("SpeedVolumeService", "Master switch is off - stopping service")
            android.util.Log.d("SpeedVolume", "Master switch off - refusing start")
            ServiceStatus.update { it.copy(running = false) }
            stopSelf()
            return START_NOT_STICKY
        }

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

        // Only tear down the restart alarm when the user actually switched the service
        // off. If the system stopped us, that alarm is the way back - cancelling it here
        // (as this used to do unconditionally) threw away our own recovery path, and
        // onTaskRemoved's quick-restart alarm was cancelled milliseconds after being
        // armed, since onTaskRemoved runs immediately before onDestroy.
        if (settingsRepository.load().masterEnabled) {
            DebugLog.d("SpeedVolumeService", "Still enabled - leaving restart alarm armed")
        } else {
            ServiceRestartAlarm.cancelRestartAlarm(this)
        }

        releaseWakeLock()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            locationManager.removeUpdates(locationListener)
            DebugLog.d("SpeedVolumeService", "Location updates stopped")
        }
        revertAllBoosts()
        ServiceStatus.update {
            it.copy(running = false, engagedTiers = List(AppSettings.TIER_COUNT) { false })
        }
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
            DebugLog.d(
                "SpeedVolumeService",
                "HEARTBEAT: Service alive, " + tierStates.mapIndexed { index, state ->
                    "tier${index + 1}=${state.engaged}(armed=${state.armed},applied=${state.appliedBoost})"
                }.joinToString(", ")
            )
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
        android.util.Log.d(
            "SpeedVolume",
            "Speed: $speedInUnit ${settings.speedUnit.name} | engaged=" +
                tierStates.mapIndexed { index, state -> "T${index + 1}=${state.engaged}" }.joinToString(",")
        )

        val now = SystemClock.elapsedRealtime()

        settings.tiers.forEachIndexed { index, tier ->
            val state = tierStates[index]
            val wasEngaged = state.engaged
            evaluateTier(tier, state, speedInUnit, now)
            if (wasEngaged != state.engaged) {
                val what = if (state.engaged) "ENGAGED (+${tier.volumeIncreaseSteps})" else "DISENGAGED"
                DebugLog.d("SpeedVolumeService", "Tier ${index + 1}: $what")
                android.util.Log.d("SpeedVolume", "Tier ${index + 1}: $what")
            }
        }

        applyVolumeChanges()
        ServiceStatus.update {
            it.copy(
                currentSpeed = speedInUnit,
                hasFix = true,
                engagedTiers = tierStates.map { state -> state.engaged }
            )
        }
        updateNotification(speedInUnit)
    }

    /**
     * Advances one tier's state machine for the latest speed reading.
     *
     * A tier engages only after the vehicle has been at or above its threshold, without
     * interruption, for the configured dwell - and only if the tier is *armed*, meaning we
     * have already seen the vehicle below that threshold at some point since this process
     * started. That arming rule is what stops a mid-drive restart from boosting: restart at
     * 10 mph with a 5 mph tier and nothing happens until you drop under 5 and cross it again.
     */
    private fun evaluateTier(tier: TierConfig, state: TierState, speedInUnit: Int, now: Long) {
        if (!tier.enabled) {
            state.engaged = false
            state.aboveSince = null
            return
        }

        if (speedInUnit < tier.speedThreshold) {
            // Below the threshold: drop any boost, and arm the tier for the next crossing.
            state.armed = true
            state.aboveSince = null
            state.engaged = false
            return
        }

        if (!state.armed) {
            // Above the threshold, but we have never seen this vehicle below it. Wait.
            state.aboveSince = null
            return
        }

        val since = state.aboveSince ?: now.also { state.aboveSince = it }
        if (!state.engaged && now - since >= tier.dwellSeconds * 1000L) {
            state.engaged = true
        }
    }

    private fun applyVolumeChanges() {
        settings.tiers.forEachIndexed { index, tier ->
            val state = tierStates[index]
            val desiredBoost = if (state.engaged) tier.volumeIncreaseSteps else 0
            val delta = desiredBoost - state.appliedBoost
            if (delta == 0) return@forEachIndexed

            val applied = adjustVolume(delta)
            // Track only what the stream actually moved. If the volume was railed at max
            // the boost never landed, so we must not subtract it back off later and drag
            // the volume below where the driver set it.
            state.appliedBoost += applied
            DebugLog.d(
                "SpeedVolumeService",
                "Tier ${index + 1} volume delta: requested=$delta applied=$applied, tracked boost=${state.appliedBoost}"
            )
            android.util.Log.d("SpeedVolume", "Volume adjustment: tier${index + 1} delta=$applied")
        }
    }

    /** Applies a relative change to the media stream, returning the steps actually moved. */
    private fun adjustVolume(deltaSteps: Int): Int {
        if (deltaSteps == 0) return 0
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val min = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (current + deltaSteps).coerceIn(min, max)
        val applied = target - current
        if (applied != 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
        DebugLog.d("SpeedVolumeService", "Volume: $current + $deltaSteps = $target (applied=$applied)")
        android.util.Log.d("SpeedVolume", "VOLUME CHANGE: $current -> $target (requested=$deltaSteps, applied=$applied)")
        return applied
    }

    private fun revertAllBoosts() {
        tierStates.forEachIndexed { index, state ->
            if (state.appliedBoost != 0) {
                DebugLog.d("SpeedVolumeService", "Reverting tier ${index + 1} boost of ${state.appliedBoost}")
                adjustVolume(-state.appliedBoost)
                state.appliedBoost = 0
            }
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
            val boost = tierStates.sumOf { it.appliedBoost }
            "$speedInUnit $unitLabel" + if (boost != 0) " · boosted +$boost" else ""
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
