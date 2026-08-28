package com.djbooya.speedvolume

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.content.Context
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private val screenReceiver = ScreenReceiver()

    private var tier1AboveSince: Long? = null
    private var tier1Engaged: Boolean = false
    private var tier2AboveSince: Long? = null
    private var tier2Engaged: Boolean = false

    private var volumeBaseline: Int = -1
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
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        settingsRepository = SettingsRepository(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLog.d("SpeedVolumeService", "onStartCommand called")
        android.util.Log.d("SpeedVolume", "onStartCommand called")
        settings = settingsRepository.load()
        DebugLog.d("SpeedVolumeService", "Settings loaded: enabled=${settings.masterEnabled}")
        android.util.Log.d("SpeedVolume", "Settings: tier1=${settings.tier1.enabled}@${settings.tier1.speedThreshold}, tier2=${settings.tier2.enabled}@${settings.tier2.speedThreshold}")

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
        startForeground(NOTIFICATION_ID, buildNotification(null))
        ServiceStatus.update {
            it.copy(running = true, speedUnit = settings.speedUnit, hasFix = false)
        }

        volumeBaseline = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        DebugLog.d("SpeedVolumeService", "Volume baseline set to: $volumeBaseline")
        android.util.Log.d("SpeedVolume", "Volume baseline: $volumeBaseline")
        startLocationUpdates()
        registerScreenReceiver()
        scheduleLocationUpdateCheck()
        ServiceRestartAlarm.scheduleRestartAlarm(this)
        DebugLog.d("SpeedVolumeService", "Service started successfully")
        android.util.Log.d("SpeedVolume", "Service started - listening for GPS")

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugLog.d("SpeedVolumeService", "Service destroyed")
        handler.removeCallbacksAndMessages(null)
        ScreenReceiver.clearService()
        ServiceRestartAlarm.cancelRestartAlarm(this)
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            DebugLog.d("SpeedVolumeService", "Screen receiver not registered: ${e.message}")
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            locationManager.removeUpdates(locationListener)
            DebugLog.d("SpeedVolumeService", "Location updates stopped")
        }
        revertAllBoosts()
        ServiceStatus.update { it.copy(running = false, tier1Engaged = false, tier2Engaged = false) }
    }

    override fun onBind(intent: Intent?) = null

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ScreenReceiver.setService(this)
        registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
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

    fun onScreenOn() {
        handler.post {
            startLocationUpdates()
        }
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
            currentTier1Boost = if (result.engaged) settings.tier1.volumeIncreaseSteps else 0
            if (stateChanged) {
                DebugLog.d("SpeedVolumeService", "Tier 1: ${if (result.engaged) "ENGAGED (+${settings.tier1.volumeIncreaseSteps})" else "DISENGAGED"}")
            }
        } else {
            currentTier1Boost = 0
        }

        if (settings.tier2.enabled) {
            val result = evaluateTier(settings.tier2, speedInUnit, now, tier2AboveSince, tier2Engaged)
            val stateChanged = tier2Engaged != result.engaged
            tier2AboveSince = result.aboveSince
            tier2Engaged = result.engaged
            currentTier2Boost = if (result.engaged) settings.tier2.volumeIncreaseSteps else 0
            if (stateChanged) {
                DebugLog.d("SpeedVolumeService", "Tier 2: ${if (result.engaged) "ENGAGED (+${settings.tier2.volumeIncreaseSteps})" else "DISENGAGED"}")
            }
        } else {
            currentTier2Boost = 0
        }

        applyVolumeBoost()
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

    /**
     * Applies boosts based on engaged tiers, respecting manual volume changes.
     * Tracks the volume baseline (user's desired base) and adds the sum of active boosts.
     */
    private fun applyVolumeBoost() {
        val targetBoost = currentTier1Boost + currentTier2Boost
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val min = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        val expectedVolume = (volumeBaseline + currentTier1Boost + currentTier2Boost).coerceIn(min, max)

        if (current != expectedVolume) {
            if (current >= min && current <= max) {
                volumeBaseline = (current - targetBoost).coerceIn(min, max)
            }
        }

        val newTarget = (volumeBaseline + targetBoost).coerceIn(min, max)
        if (current != newTarget) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newTarget, 0)
            DebugLog.d("SpeedVolumeService", "Volume changed: $current -> $newTarget (baseline=$volumeBaseline, boost=$targetBoost)")
            android.util.Log.d("SpeedVolume", "VOLUME: $current -> $newTarget (tier1=$currentTier1Boost + tier2=$currentTier2Boost)")
        } else if (targetBoost > 0) {
            DebugLog.d("SpeedVolumeService", "Volume already at target: $current (boost=$targetBoost)")
            android.util.Log.d("SpeedVolume", "VOLUME STABLE: $current (target was $newTarget)")
        }
    }

    private fun revertAllBoosts() {
        if (tier1Engaged || tier2Engaged) {
            currentTier1Boost = 0
            currentTier2Boost = 0
            applyVolumeBoost()
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
    }
}
