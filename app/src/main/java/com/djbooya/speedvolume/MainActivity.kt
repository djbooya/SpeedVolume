package com.djbooya.speedvolume

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
        populateFromSettings(settingsRepository.load())

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

        binding.buttonSave.setOnClickListener {
            DebugLog.d("MainActivity", "Save button pressed")
            val settings = collectFromUi()
            DebugLog.d("MainActivity", "Saving settings: enabled=${settings.masterEnabled}, tier1=${settings.tier1.enabled}, tier2=${settings.tier2.enabled}")
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
                            val tiers = buildString {
                                if (state.tier1Engaged) append(" · Tier 1 boost")
                                if (state.tier2Engaged) append(" · Tier 2 boost")
                            }
                            "${state.currentSpeed} $unit$tiers"
                        }
                    }
                }
            }
        }
    }

    private fun populateFromSettings(settings: AppSettings) {
        binding.switchMaster.isChecked = settings.masterEnabled
        binding.radioKmh.isChecked = settings.speedUnit == SpeedUnit.KMH
        binding.radioMph.isChecked = settings.speedUnit == SpeedUnit.MPH

        binding.checkTier1Enabled.isChecked = settings.tier1.enabled
        binding.editTier1Speed.setText(settings.tier1.speedThreshold.toString())
        binding.editTier1Increase.setText(settings.tier1.volumeIncreaseSteps.toString())
        binding.editTier1Dwell.setText(settings.tier1.dwellSeconds.toString())

        binding.checkTier2Enabled.isChecked = settings.tier2.enabled
        binding.editTier2Speed.setText(settings.tier2.speedThreshold.toString())
        binding.editTier2Increase.setText(settings.tier2.volumeIncreaseSteps.toString())
        binding.editTier2Dwell.setText(settings.tier2.dwellSeconds.toString())

        binding.checkStartOnBoot.isChecked = settings.startOnBoot
    }

    private fun collectFromUi(): AppSettings {
        val tier1 = TierConfig(
            enabled = binding.checkTier1Enabled.isChecked,
            speedThreshold = binding.editTier1Speed.text.toString().toIntOrNull() ?: 45,
            volumeIncreaseSteps = binding.editTier1Increase.text.toString().toIntOrNull() ?: 3,
            dwellSeconds = binding.editTier1Dwell.text.toString().toIntOrNull() ?: 5
        )
        val tier2 = TierConfig(
            enabled = binding.checkTier2Enabled.isChecked,
            speedThreshold = binding.editTier2Speed.text.toString().toIntOrNull() ?: 90,
            volumeIncreaseSteps = binding.editTier2Increase.text.toString().toIntOrNull() ?: 4,
            dwellSeconds = binding.editTier2Dwell.text.toString().toIntOrNull() ?: 5
        )
        if (tier1.enabled && tier2.enabled && tier2.speedThreshold <= tier1.speedThreshold) {
            Toast.makeText(this, R.string.tier2_order_warning, Toast.LENGTH_LONG).show()
        }
        return AppSettings(
            masterEnabled = binding.switchMaster.isChecked,
            speedUnit = if (binding.radioMph.isChecked) SpeedUnit.MPH else SpeedUnit.KMH,
            startOnBoot = binding.checkStartOnBoot.isChecked,
            tier1 = tier1,
            tier2 = tier2
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
}
