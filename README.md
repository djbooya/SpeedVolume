# Speed Volume

A lightweight Android foreground service for car head units that dynamically adjusts media volume based on vehicle speed from GPS.

## Features

- **GPS-based speed detection** — reads vehicle speed from GPS (compatible with aftermarket head units that lack Google Play Services)
- **Two-tier volume control** — independently configure speed thresholds and volume boosts for two speed ranges
- **Dwell timer** — adjustable hold time before each tier's boost engages (prevents jitter from speed fluctuations)
- **Preserves manual adjustments** — if you manually change volume while boosted, the app respects your preference and adjusts relative to your manual setting
- **Boot auto-start** — optional automatic service start after radio reboot
- **Live status display** — shows current speed and active boost tiers in the settings UI
- **Persistent settings** — all configuration saved across reboots

## Installation

1. Download the latest APK (`SpeedVolume-*.apk`) - https://github.com/djbooya/SpeedVolume/tree/main/app/build/outputs/apk/debug
2. Copy to a USB drive or radio storage
3. On the radio, enable "install unknown apps" for your file manager and open the APK
4. Grant location permission
5. Tap "Disable battery optimization" to ensure the service stays alive
6. Tap "Allow exact alarms" so the auto-resume mechanism can fire reliably (Android 12+)
7. Configure your speed thresholds and volume deltas
8. Tap **Save** to apply and start the service

**Cancel** discards any changes made since launch.

## Configuration

**Master Switch**
Enable/disable the entire speed-volume service.

**Speed Unit**
Choose km/h or mph for all threshold values.

**Tier 1 & Tier 2**
Each tier has four fields:
- **Enabled** — checkbox to turn this tier on/off
- **Speed Threshold** — trigger speed (in selected unit)
- **Volume Increase** — number of volume steps to add (1–15)
- **Hold Time** — how long (seconds) to stay at or above threshold before boosting

Tiers stack: if you're above both thresholds, both boosts apply simultaneously.

**Start on Boot**
If checked, the service restarts automatically after a radio reboot (requires location permission already granted).

**Permissions**
- Grant location access so the service can read GPS speed
- Disable battery optimization to prevent aggressive background task killing

## How It Works

1. Service reads GPS speed every ~1 second
2. When speed enters a tier's range and stays there for the "hold time," that tier's volume boost applies
3. When speed drops below the threshold, the boost is reverted
4. If you manually adjust volume while boosted, the app remembers your adjustment as the new "baseline" and continues to apply/remove boosts relative to that baseline
5. When the radio resumes from sleep, location updates are re-enabled
6. Settings are saved to device storage and survive reboots if "start on boot" is enabled

## Permissions Used

- `ACCESS_FINE_LOCATION` — GPS speed reading
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` — required for continuous background GPS
- `POST_NOTIFICATIONS` — persistent notification while service runs
- `RECEIVE_BOOT_COMPLETED` — auto-start after radio reboot
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — preserve service across battery optimization

## Debug Logs

The app writes detailed debug logs to help troubleshoot boot and runtime issues.

**Log Location:** `Android/data/com.djbooya.speedvolume.debug/files/logs/speedvolume.log` (accessible via File Manager)

**Log Contents:**
- Boot broadcast reception and service start decisions
- Service lifecycle events (onCreate, onStartCommand, onDestroy)
- Location provider availability and GPS/Network updates
- Permission checks
- Tier engagement/disengagement with speed values
- Volume baseline adjustments

**Automatic Cleanup:** Logs older than 24 hours are automatically deleted on service startup.

**How to Access:**
1. Open File Manager on the radio
2. Navigate to: `Android/data/com.djbooya.speedvolume.debug/files/logs/`
3. Open `speedvolume.log` with a text editor
4. Look for ERROR/WARN messages to diagnose issues

## Release Notes

### v1.7 (Aug 31, 2026)
- **Added:** `goAsync()` in `BootReceiver`, `ScreenWakeReceiver`, `ConnectivityChangeReceiver`, and `PackageUpdateReceiver` — extends each receiver's execution window past `onReceive()` returning (up to ~10s), giving a cold-started process more headroom to finish calling `startForegroundService()` before the system can reap it. Adopted after decompiling an aftermarket head-unit dashboard app's manifest/bytecode to see how it survives sleep - it turned out to be a privileged system-signed launcher app (`android.uid.system`, not comparable to a sideloaded third-party service), but its `BroadcastReceiver` pattern of pairing `goAsync()` with a `Handler` was a legitimate, transferable technique
- **Download:** [SpeedVolume-1.7-debug.apk](https://github.com/djbooya/SpeedVolume/raw/main/app/build/outputs/apk/debug/SpeedVolume-1.7-debug.apk)

### v1.6 (Aug 31, 2026)
- **Fixed:** Service resume from sleep — logs showed the 30-minute restart alarm, screen-wake receiver, connectivity-change receiver, and package-update receiver never actually fired even once across a 4.5-hour trace; only manually reopening the app recovered the service
- **Changed:** Restart alarm now uses `setExactAndAllowWhileIdle` (when the exact-alarm permission is granted) instead of the inexact `setAndAllowWhileIdle`, which OEM battery management can defer indefinitely
- **Changed:** Restart-check interval shortened from 30 minutes to 5 minutes so a killed service recovers much faster
- **Added:** `onTaskRemoved()` handling — if the head unit clears recent tasks (or the app is swiped away), a short exact alarm restarts the service a few seconds later, since restarting inline in that callback often gets killed along with the process
- **Added:** "Allow exact alarms" permission button (Android 12+) — required for the restart alarm to fire reliably
- **Added:** Battery-optimization and exact-alarm status now logged at service startup for future diagnosis
- **Note:** If the service still doesn't resume, check your head unit's manufacturer-specific "auto-start"/"protected apps"/background app manager — many aftermarket ROMs maintain a separate kill-list outside standard Android battery optimization that the app can't override from code
- **Download:** [SpeedVolume-1.6-debug.apk](https://github.com/djbooya/SpeedVolume/raw/main/app/build/outputs/apk/debug/SpeedVolume-1.6-debug.apk)

### v1.5 (Aug 28, 2026)
- **Fixed:** View Logs button now visible and full-width on main screen
- **Fixed:** Version number now large and centered for easy visibility
- **Added:** AlarmManager to restart service every 30 minutes if killed
- **Improved:** Service now survives device sleep/power cycle
- **Fixed:** Resume from sleep — alarm wakes service when device resumes
- **Download:** [SpeedVolume-1.5-debug.apk](https://github.com/djbooya/SpeedVolume/raw/main/app/build/outputs/apk/debug/SpeedVolume-1.5-debug.apk)

### v1.4 (Aug 27, 2026)
- **Added:** Version number display on main screen for easy identification
- **Added:** "View Logs" button to open debug log file directly from app
- **Improved:** Users can now quickly access logs without file manager navigation
- **Download:** [SpeedVolume-1.4-debug.apk](https://github.com/djbooya/SpeedVolume/raw/main/app/build/outputs/apk/debug/SpeedVolume-1.4-debug.apk)

### v1.3 (Aug 26, 2026)
- **Added:** Comprehensive debug logging to `Android/data/com.djbooya.speedvolume.debug/files/logs/speedvolume.log`
- **Added:** Detailed logs for boot events, service lifecycle, location updates, and tier state changes
- **Added:** Automatic cleanup of logs older than 24 hours
- **Improved:** Boot and resume diagnostics — logs show exactly why service may not start or continue running
- **Download:** [SpeedVolume-1.3-debug.apk](https://github.com/djbooya/SpeedVolume/raw/main/app/build/outputs/apk/debug/SpeedVolume-1.3-debug.apk)

### v1.2 (Aug 26, 2026)
- **Improved:** Permission buttons now show confirmation toast if permission is already granted (user knows nothing needs to happen)
- **Download:** [SpeedVolume-1.2-debug.apk](https://github.com/djbooya/SpeedVolume/raw/main/app/build/outputs/apk/debug/SpeedVolume-1.2-debug.apk)


## Troubleshooting

**Service not starting on boot**
- Ensure location permission is already granted before reboot
- Check that "Start on boot" is enabled in settings and you tap **Save** (not Cancel)
- Some radio ROMs have aggressive auto-start managers — whitelist Speed Volume in any such app

**Service stops after radio sleeps**
- v1.1+ automatically re-enables location tracking when the device wakes
- v1.6+ uses an exact restart alarm (5-minute check interval) plus an `onTaskRemoved()` handler as backup — make sure "Allow exact alarms" and "Disable battery optimization" are both granted
- If still having issues, check your head unit's manufacturer-specific "auto-start"/"protected apps" manager — many aftermarket ROMs kill background apps outside of standard Android battery optimization, which the app cannot override from code

**Volume boost not applying**
- Ensure the service is actually running (check persistent notification)
- Check that location fix is acquired (notification should show speed and unit when active)
- Verify the speed threshold and hold time are appropriate for your driving

**Manual volume adjustments get overwritten**
- v1.1+ preserves manual volume changes as the new baseline for future boosts/reverts
- If the issue persists, restart the service from the settings screen

## Building from Source

```bash
cd SpeedVolume
export JAVA_HOME="/path/to/Android/Studio/jbr"
./gradlew assembleDebug
```

Output APK: `app/build/outputs/apk/debug/SpeedVolume-1.7-debug.apk`

For a release build:
```bash
./gradlew assembleRelease
```

(Requires a keystore — see Android documentation on signing.)

## License

No license — provided as-is for personal use on your radio.

## Support

For issues with boot startup or resume, ensure:
1. Location permission is granted before boot
2. Battery optimization is disabled for the app
3. "Start on boot" is checked and changes were saved (not cancelled)
