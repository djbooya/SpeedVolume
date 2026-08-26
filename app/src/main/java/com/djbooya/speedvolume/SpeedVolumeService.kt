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
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlin.math.roundToInt

/**
 * Foreground service that reads GPS speed and nudges the media volume up
 * in up to two configurable tiers, reverting each tier's boost once speed
 * drops back below its threshold.
 */
class SpeedVolumeService : Service() {

    private lateinit var locationManager: LocationManager
    private lateinit var audioManager: AudioManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var settings: AppSettings

    private var tier1AboveSince: Long? = null
    private var tier1Engaged: Boolean = false
    private var tier2AboveSince: Long? = null
    private var tier2Engaged: Boolean = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocation(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        settingsRepository = SettingsRepository(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        settings = settingsRepository.load()

        startForeground(NOTIFICATION_ID, buildNotification(null))
        ServiceStatus.update {
            it.copy(running = true, speedUnit = settings.speedUnit, hasFix = false)
        }
        startLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.removeUpdates(locationListener)
        }
        // Revert any active boosts so we don't leave the volume artificially high.
        if (tier1Engaged) {
            adjustVolume(-settings.tier1.volumeIncreaseSteps)
            tier1Engaged = false
        }
        if (tier2Engaged) {
            adjustVolume(-settings.tier2.volumeIncreaseSteps)
            tier2Engaged = false
        }
        ServiceStatus.update { it.copy(running = false, tier1Engaged = false, tier2Engaged = false) }
    }

    override fun onBind(intent: Intent?) = null

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        val providers = locationManager.getProviders(true)
        if (LocationManager.GPS_PROVIDER in providers) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_UPDATE_MS, 0f, locationListener)
        }
        if (LocationManager.NETWORK_PROVIDER in providers) {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, MIN_UPDATE_MS, 0f, locationListener)
        }
    }

    private fun handleLocation(location: Location) {
        if (!location.hasSpeed()) return

        val speedMps = location.speed
        val speedInUnit = when (settings.speedUnit) {
            SpeedUnit.KMH -> speedMps * 3.6
            SpeedUnit.MPH -> speedMps * 2.23694
        }.roundToInt()

        val now = SystemClock.elapsedRealtime()

        if (settings.tier1.enabled) {
            val result = evaluateTier(settings.tier1, speedInUnit, now, tier1AboveSince, tier1Engaged)
            tier1AboveSince = result.aboveSince
            tier1Engaged = result.engaged
        }
        if (settings.tier2.enabled) {
            val result = evaluateTier(settings.tier2, speedInUnit, now, tier2AboveSince, tier2Engaged)
            tier2AboveSince = result.aboveSince
            tier2Engaged = result.engaged
        }

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

    /** Runs the dwell-timer state machine for one tier and applies/reverts its volume delta. */
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
                adjustVolume(tier.volumeIncreaseSteps)
                return TierEvalResult(since, true)
            }
            return TierEvalResult(since, currentlyEngaged)
        } else {
            if (currentlyEngaged) {
                adjustVolume(-tier.volumeIncreaseSteps)
            }
            return TierEvalResult(null, false)
        }
    }

    private fun adjustVolume(deltaSteps: Int) {
        if (deltaSteps == 0) return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val min = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (current + deltaSteps).coerceIn(min, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
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
    }
}
