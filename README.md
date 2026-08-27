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

1. Download the latest APK (`SpeedVolume-*.apk`)
2. Copy to a USB drive or radio storage
3. On the radio, enable "install unknown apps" for your file manager and open the APK
4. Grant location permission
5. Tap "Disable battery optimization" to ensure the service stays alive
6. Configure your speed thresholds and volume deltas
7. Tap **Save** to apply and start the service

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

### v1.3 (Aug 26, 2024)
- **Added:** Comprehensive debug logging to `Android/data/com.djbooya.speedvolume.debug/files/logs/speedvolume.log`
- **Added:** Detailed logs for boot events, service lifecycle, location updates, and tier state changes
- **Added:** Automatic cleanup of logs older than 24 hours
- **Improved:** Boot and resume diagnostics — logs show exactly why service may not start or continue running
- **Updated:** APK filename to `SpeedVolume-1.3-debug.apk`

### v1.2 (Aug 26, 2024)
- **Improved:** Permission buttons now show confirmation toast if permission is already granted (user knows nothing needs to happen)
- **Updated:** APK filename updated to `SpeedVolume-1.2-debug.apk`

### v1.2 (Aug 26, 2024)
- **Improved:** Permission buttons now show confirmation toast if permission is already granted (user knows nothing needs to happen)
- **Updated:** APK filename updated to `SpeedVolume-1.2-debug.apk`

### v1.1 (Aug 25, 2024)
- **Fixed:** Service now starts on boot even if location permission was not yet granted (service checks permission at start)
- **Fixed:** Service resumes location tracking after device wakes from sleep (detects SCREEN_ON and re-enables GPS)
- **Improved:** Volume boost now tracks baseline volume — manual adjustments by user are preserved when boosts are applied/removed
- **Updated:** APK filename now includes version (e.g., `SpeedVolume-1.1-debug.apk`)

### v1.0 (Aug 23, 2024)
- **Initial release**
- GPS-based volume scaling with two configurable tiers
- Dwell timer to prevent boost churn
- Foreground service with persistent notification
- Settings UI with Save/Cancel buttons
- Boot auto-start option

## Troubleshooting

**Service not starting on boot**
- Ensure location permission is already granted before reboot
- Check that "Start on boot" is enabled in settings and you tap **Save** (not Cancel)
- Some radio ROMs have aggressive auto-start managers — whitelist Speed Volume in any such app

**Service stops after radio sleeps**
- v1.1+ automatically re-enables location tracking when the device wakes
- If still having issues, check battery optimization settings and ensure the app is whitelisted

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

Output APK: `app/build/outputs/apk/debug/SpeedVolume-1.3-debug.apk`

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
