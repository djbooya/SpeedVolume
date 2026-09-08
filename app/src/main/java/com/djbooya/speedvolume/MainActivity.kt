package com.djbooya.speedvolume

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.djbooya.speedvolume.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsRepository: SettingsRepository

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            maybeRequestNotificationPermission()
        } else {
            binding.switchMaster.isChecked = false
            Toast.makeText(this, R.string.permission_rationale, Toast.LENGTH_LONG).show()
        }
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* notification permission is best-effort; ignore result */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settingsRepository = SettingsRepository(this)
        DebugLog.init(this)
        DebugLog.d("MainActivity", "App opened")

        applyStatusBarInsetPadding()
        DebugLog.d("MainActivity", "=== APP OPENED v2.0 ===")
        android.util.Log.d("SpeedVolume", "=== APP OPENED v2.0 ===")

        val settings = settingsRepository.load()
        populateFromSettings(settings)
        updateVersionDisplay()

        // Auto-restart service if enabled but not running
        if (settings.masterEnabled && !ServiceStatus.state.value.running) {
            if (hasLocationPermission()) {
                DebugLog.d("MainActivity", "Service auto-restart: Detected stopped service, restarting")
                android.util.Log.d("SpeedVolume", "APP OPEN: Auto-restarting stopped service")
                startServiceCompat()
                Toast.makeText(this, "Service auto-restarted", Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonViewLogs.setOnClickListener { viewLogs() }

        binding.switchMaster.setOnCheckedChangeListener { _, isChecked ->
            DebugLog.d("MainActivity", "Master switch toggled: $isChecked")
            if (isChecked && !hasLocationPermission()) {
                DebugLog.d("MainActivity", "Location permission not granted, requesting")
                requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            } else if (isChecked) {
                DebugLog.d("MainActivity", "Location permission granted, checking notification permission")
                maybeRequestNotificationPermission()
            }
        }

        binding.buttonGrantLocation.setOnClickListener {
            if (hasLocationPermission()) {
                Toast.makeText(this, "Location permission already granted.", Toast.LENGTH_SHORT).show()
            } else {
                requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        binding.buttonIgnoreBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }

        binding.buttonGrantExactAlarm.setOnClickListener { requestExactAlarmPermission() }

        binding.buttonSave.setOnClickListener {
            DebugLog.d("MainActivity", "Save button pressed")
            val settings = collectFromUi()
            val tierSummary = settings.tiers.mapIndexed { index, tier ->
                "tier${index + 1}=${tier.enabled}"
            }.joinToString(", ")
            DebugLog.d("MainActivity", "Saving settings: enabled=${settings.masterEnabled}, $tierSummary")
            settingsRepository.save(settings)
            applyServiceState(settings)
            finish()
        }

        binding.buttonCancel.setOnClickListener {
            DebugLog.d("MainActivity", "Cancel button pressed - discarding changes")
            // Discard any in-memory UI changes; nothing was persisted, nothing to undo.
            finish()
        }

        observeServiceStatus()
    }

    /**
     * Pads the content below the status bar even when it's hidden/translucent — targeting
     * SDK 35 makes the window draw edge-to-edge by default, so without this the master
     * switch can end up under the status bar (or flush against the top on devices that
     * report a zero inset while the bar is hidden).
     */
    private fun applyStatusBarInsetPadding() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val density = resources.displayMetrics.density
        val basePaddingPx = (20 * density).toInt()
        val minTopPaddingPx = (24 * density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.contentContainer) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                top = basePaddingPx + maxOf(bars.top, minTopPaddingPx),
                bottom = basePaddingPx + bars.bottom
            )
            insets
        }
    }

    private fun observeServiceStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceStatus.state.collect { state ->
                    binding.textStatus.text = when {
                        !state.running -> getString(R.string.status_stopped)
                        !state.hasFix -> getString(R.string.status_waiting)
                        else -> {
                            val unit = if (state.speedUnit == SpeedUnit.KMH) "km/h" else "mph"
                            val tiers = state.engagedTiers
                                .mapIndexedNotNull { index, engaged ->
                                    if (engaged) " · Tier ${index + 1} boost" else null
                                }
                                .joinToString("")
                            "${state.currentSpeed} $unit$tiers"
                        }
                    }
                }
            }
        }
    }

    /** The four editable fields of one tier row, so the tiers can be handled in a loop. */
    private class TierViews(
        val enabled: CheckBox,
        val speed: EditText,
        val increase: EditText,
        val dwell: EditText
    )

    private fun tierViews(): List<TierViews> = listOf(
        TierViews(binding.checkTier1Enabled, binding.editTier1Speed, binding.editTier1Increase, binding.editTier1Dwell),
        TierViews(binding.checkTier2Enabled, binding.editTier2Speed, binding.editTier2Increase, binding.editTier2Dwell),
        TierViews(binding.checkTier3Enabled, binding.editTier3Speed, binding.editTier3Increase, binding.editTier3Dwell),
        TierViews(binding.checkTier4Enabled, binding.editTier4Speed, binding.editTier4Increase, binding.editTier4Dwell)
    )

    private fun populateFromSettings(settings: AppSettings) {
        binding.switchMaster.isChecked = settings.masterEnabled
        binding.radioKmh.isChecked = settings.speedUnit == SpeedUnit.KMH
        binding.radioMph.isChecked = settings.speedUnit == SpeedUnit.MPH

        tierViews().forEachIndexed { index, views ->
            val tier = settings.tiers[index]
            views.enabled.isChecked = tier.enabled
            views.speed.setText(tier.speedThreshold.toString())
            views.increase.setText(tier.volumeIncreaseSteps.toString())
            views.dwell.setText(tier.dwellSeconds.toString())
        }

        binding.checkStartOnBoot.isChecked = settings.startOnBoot
    }

    private fun collectFromUi(): AppSettings {
        val tiers = tierViews().mapIndexed { index, views ->
            val default = SettingsRepository.TIER_DEFAULTS[index]
            TierConfig(
                enabled = views.enabled.isChecked,
                speedThreshold = views.speed.text.toString().toIntOrNull() ?: default.speedThreshold,
                volumeIncreaseSteps = views.increase.text.toString().toIntOrNull() ?: default.volumeIncreaseSteps,
                dwellSeconds = views.dwell.text.toString().toIntOrNull() ?: default.dwellSeconds
            )
        }

        // Boosts stack, so each enabled tier is expected to sit above the previous one.
        // This is only a warning - the service copes either way.
        val enabledTiers = tiers.filter { it.enabled }
        val outOfOrder = enabledTiers.zipWithNext().any { (lower, higher) ->
            higher.speedThreshold <= lower.speedThreshold
        }
        if (outOfOrder) {
            Toast.makeText(this, R.string.tier_order_warning, Toast.LENGTH_LONG).show()
        }

        return AppSettings(
            masterEnabled = binding.switchMaster.isChecked,
            speedUnit = if (binding.radioMph.isChecked) SpeedUnit.MPH else SpeedUnit.KMH,
            startOnBoot = binding.checkStartOnBoot.isChecked,
            tiers = tiers
        )
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /** Applies the just-saved settings to the running service: (re)start, or stop. */
    private fun applyServiceState(settings: AppSettings) {
        DebugLog.d("MainActivity", "Applying service state: enabled=${settings.masterEnabled}")
        stopService(Intent(this, SpeedVolumeService::class.java))
        DebugLog.d("MainActivity", "Service stopped (if running)")
        if (settings.masterEnabled) {
            if (hasLocationPermission()) {
                DebugLog.d("MainActivity", "Starting service with location permission")
                startServiceCompat()
            } else {
                DebugLog.w("MainActivity", "Cannot start service - location permission missing")
                Toast.makeText(this, R.string.permission_rationale, Toast.LENGTH_LONG).show()
            }
        } else {
            DebugLog.d("MainActivity", "Service disabled by user")
        }
    }

    private fun startServiceCompat() {
        ContextCompat.startForegroundService(this, Intent(this, SpeedVolumeService::class.java))
    }

    private fun requestIgnoreBatteryOptimizations() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Battery optimization already disabled for this app.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Toast.makeText(this, "Exact alarms already allowed on this Android version.", Toast.LENGTH_SHORT).show()
            return
        }
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        if (alarmManager.canScheduleExactAlarms()) {
            Toast.makeText(this, "Exact alarms already allowed.", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
    }

    private fun updateVersionDisplay() {
        binding.textVersion.text = "Version: 2.0"
    }

    private fun viewLogs() {
        val logFile = DebugLog.getLogFile()
        if (logFile == null || !logFile.exists()) {
            Toast.makeText(this, "No logs available yet.", Toast.LENGTH_SHORT).show()
            DebugLog.d("MainActivity", "View Logs: Log file not found")
            return
        }

        DebugLog.d("MainActivity", "Opening log file: ${logFile.absolutePath}")
        try {
            val fileUri = FileProvider.getUriForFile(
                this,
                "com.djbooya.speedvolume.debug.fileprovider",
                logFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            DebugLog.e("MainActivity", "Failed to open log file", e)
            Toast.makeText(
                this,
                "Cannot open logs. Log file location:\n${logFile.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
