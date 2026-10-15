# Speed Volume

A lightweight Android foreground service for car head units that dynamically adjusts media volume based on vehicle speed from GPS.

## Features

- **GPS-based speed detection** — reads vehicle speed from GPS (compatible with aftermarket head units that lack Google Play Services)
- **Four-tier volume control** — independently configure speed thresholds and volume boosts for four speed ranges, which stack
- **Dwell timer** — adjustable hold time before each tier's boost engages (prevents jitter from speed fluctuations)
- **Preserves manual adjustments** — if you change the volume while boosted, the app re-detects the radio's new volume and keeps the boost on top of it
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
9. If your unit force-stops apps on sleep, set up the Automate watchdog — see [Running under Automate](#running-under-automate)

**Cancel** discards any changes made since launch.

## Configuration

**Master Switch**
Enable/disable the entire speed-volume service.

**Speed Unit**
Choose km/h or mph for all threshold values.

**Tier 1 – Tier 4**
Each tier has four fields:
- **Enabled** — checkbox to turn this tier on/off
- **Speed Threshold** — trigger speed (in selected unit)
- **Volume Increase** — number of volume steps to add (1–15)
- **Hold Time** — how long (seconds) to stay at or above threshold before boosting

Tiers stack: if you're above three thresholds, all three boosts apply simultaneously. Set
each enabled tier's threshold higher than the one before it (the app warns if you don't).

**Start on Boot**
If checked, the service restarts automatically after a radio reboot (requires location permission already granted).

**Permissions**
- Grant location access so the service can read GPS speed
- Disable battery optimization to prevent aggressive background task killing

## How It Works

1. Service reads GPS speed every ~1 second
2. When speed reaches a tier's threshold and stays there for the "hold time," that tier's volume boost applies
3. When speed drops below the threshold, that tier's boost is reverted
4. Settings are saved to device storage and survive reboots if "start on boot" is enabled

### Volume arithmetic

The app keeps track of two numbers separately: the **radio's volume** (what its display shows)
and the **boost** the engaged tiers add on top. The output is always set to
*radio volume + boost* as a single value, rather than stepped up and down.

**The number on screen will not move when a boost applies.** The head unit keeps its own
on-screen volume counter, so while a boost is active the displayed number stays where you left
it and the actual output sits above it by the boost amount.

**When you turn the knob, the head unit writes its own on-screen number straight into the
output** — which wipes any boost out of it. So before every adjustment the app re-reads the
output, the same way it does when the service starts. If it no longer matches what the app
last set, the app takes that reading as the radio's new volume and puts the boost back on top
straight away.

With Tier 1 at 5 mph / +1 / 1 s, starting from a displayed volume of 10:

| | Action | Displayed | Effective |
|---|---|---|---|
| | Sitting still | 10 | 10 |
| | 5 mph held for 2 s → Tier 1 engages | 10 | **11** |
| 1 | Slow to 4 mph → boost reverts | 10 | **10** |
| 2 | From boosted, turn volume down one step … | 9 | 10 |
| | … then slow to 4 mph | 9 | **9** |
| 3 | From boosted, turn volume up two steps … | 12 | 13 |
| | … then slow to 4 mph | 12 | **12** |

In cases 2 and 3 the boost comes off wherever *you* left the radio, so your manual change
survives. Additional tiers behave identically and stack — with several tiers engaged the
displayed number can sit well below the actual output until you slow down.

**A boost is never subtracted unless it was actually applied.** If the volume is already at
maximum when a tier engages, the boost cannot land — so when that tier disengages the output
returns to the radio's volume and never drops below it.

> **One change the app cannot see:** turning the knob to *exactly* the value the output is
> already at while boosted. With a +1 boost and the radio at 10 the output is 11; press up once
> and the head unit writes 11 — the same value, so Android never reports a change. The app still
> believes the radio is at 10, so when the boost comes off the output drops to 10 while the
> display shows 11. Pressing again (or any other change) puts it back in sync. This is a limit of
> what Android reports, not something the app can work around.

### Restarting mid-drive

Every tier starts **disarmed**, and a tier can only engage after the app has seen the vehicle
*below* that tier's threshold. So if the service restarts while you are already moving:

- Nothing is boosted, and nothing is subtracted — volume stays exactly where the radio has it
- Restarting at 10 mph with a 5 mph tier does **not** boost
- That tier boosts only once you drop below 5 mph and then cross it again

This is why a restart mid-drive is safe: a fresh process knows it has applied nothing, so it
can never "revert" a boost it did not make.

## Permissions Used

- `ACCESS_FINE_LOCATION` — GPS speed reading
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` — required for continuous background GPS
- `POST_NOTIFICATIONS` — persistent notification while service runs
- `RECEIVE_BOOT_COMPLETED` — auto-start after radio reboot
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — preserve service across battery optimization

## Running under Automate

**Required on head units that force-stop apps on sleep.** If the service stops every time
the radio sleeps and only comes back when you open the app by hand, this section is for you.

### Why this is necessary

Some head units don't just kill background apps on sleep — they **force-stop** the package.
Android treats a force-stopped package very differently from a killed one:

- All its pending `AlarmManager` alarms are **deleted**
- All its manifest-declared broadcast receivers are **blocked** (`BOOT_COMPLETED`,
  `SCREEN_ON`, `CONNECTIVITY_CHANGE`, etc. are never delivered)
- It stays in that state until **a user or another app explicitly launches it**

This is by design and it is not something an app can work around from the inside. SpeedVolume
has five independent self-restart mechanisms and on a force-stopping unit **none of them can
fire** — verified across a 2,881-line log where every single recovery was a manual app open.

The fix is an external watchdog. [LlamaLab Automate](https://llamalab.com/automate) is a free
automation app that survives on these units, and an explicit service start from Automate
clears the stopped state and brings SpeedVolume back.

### Verifying your unit force-stops the app

With ADB enabled on the radio, let it sleep until the service dies, then — **before** opening
the app — run:

```bash
adb shell dumpsys package com.djbooya.speedvolume.debug | grep -i stopped
```

`stopped=true` confirms it. (For a release build, drop the `.debug` suffix.)

### Setup

**1. Install Automate** from Google Play or its APK, and open it once.

**2. Exempt Automate from battery optimization** — System Settings → Apps → Automate →
Battery → allow background/unrestricted. Automate's own
[FAQ](https://llamalab.com/automate/doc/faq.html) lists the exact path for many OEM skins.

**3. Create a new flow** (＋ in Automate) with these four blocks:

```
   ┌─────────────────────────────┐
   │ Flow beginning              │
   └──────────────┬──────────────┘
                  ↓
   ┌─────────────────────────────┐
   │ Service start               │   ← restarts SpeedVolume
   │   Package:  com.djbooya.speedvolume.debug
   │   Class:    com.djbooya.speedvolume.SpeedVolumeService
   │   Foreground: Yes
   └──────────────┬──────────────┘
                  ↓
   ┌─────────────────────────────┐
   │ Delay                       │
   │   5 minutes                 │
   └──────────────┬──────────────┘
                  ↓
          (loop back to Service start)
```

**4. Start the flow** and enable **Run on system startup** in Automate's settings
(≡ menu → Settings) so the watchdog itself returns after a reboot.

### Block settings in detail

**Service start** — the important one. Fill in exactly:

| Field | Value |
|---|---|
| Package | `com.djbooya.speedvolume.debug` |
| Class | `com.djbooya.speedvolume.SpeedVolumeService` |
| Foreground | **Yes** |

> ⚠️ **The package and class names do not match.** The debug build appends `.debug` to the
> *application ID* only — the Java class name never changes. Using
> `com.djbooya.speedvolume.debug.SpeedVolumeService` will fail with a class-not-found error.
> For a release build the package is `com.djbooya.speedvolume` and the class is unchanged.

> ⚠️ **Foreground must be Yes.** SpeedVolume is a foreground service; starting it with
> Foreground = No calls `startService()`, which throws `IllegalStateException` when the app
> is in the background on Android 8+.

**Delay** — 5 minutes is a good default. Starting an already-running service is harmless
(it just re-runs `onStartCommand`, which is idempotent), so a shorter interval only costs
a little CPU. This is the fallback that guarantees recovery regardless of what events your
unit emits.

### Faster recovery on wake (optional)

The 5-minute loop means up to 5 minutes of no volume control after waking. To recover
within seconds, insert a **Display on** block set to **When changed → On** immediately
before `Service start`, and run it as a second flow alongside the timed one. Keep the timed
loop as well — some units never emit display events at all.

### Confirming it works

After a sleep/wake cycle, open the log (View Logs) and look for a `=== SERVICE STARTED ===`
line that is **not** preceded by `=== APP OPENED ===`. That is Automate restarting the
service rather than you doing it by hand.

## Debug Mode

Because the radio's on-screen volume doesn't move when a boost applies (see
[Volume arithmetic](#volume-arithmetic)), there is no way to see the effective volume by eye.
Debug mode shows it.

**Turn it on:** tap the **⋮** menu at the top of the app → check **Debug mode**. It takes
effect immediately — no need to tap Save, and it does not restart the service.

**What you'll see:** a toast showing the volume the app believes the radio is outputting, every
time a tier's boost applies or is removed **and** every time you turn the volume yourself. With
the radio at 10 and Tier 1 set to +1:

| Event | Toast |
|---|---|
| Tier 1 engages | `Effective volume: 11 (radio 10 + boost 1)` |
| You turn the volume down one step | `Effective volume: 10 (radio 9 + boost 1)` |
| Drop below Tier 1 | `Effective volume: 9 (no boost)` |

"Radio" is the number the head unit itself is showing. Toasts appear only when the volume
actually changes, not on every GPS reading. When several tiers change at once you get one toast
with the final level, and holding the volume button shows just the latest value rather than
stacking toasts. Every toast is also written to the log as a `Debug toast:` line, and manual
changes are logged as `Manual volume change:` lines even when debug mode is off.

> If boost toasts appear but knob turns never produce one, your head unit's volume control
> isn't going through Android's media volume. The boost arithmetic is unaffected, but the app
> can't see those knob changes.

**Turn it off** the same way. With debug mode off, no volume toasts are shown at all.

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

### v2.3 (Oct 15, 2026)
- **Fixed:** Volume tracking broke after the first manual volume change. Turning the knob makes the head unit write its own on-screen number straight into the output, wiping the boost — but the app still believed the boost was there, so it later subtracted a boost that was gone and dropped the volume below where you set it
- **Changed:** The app now tracks the radio's volume and the boost separately, and sets the output to *radio + boost* as one value instead of stepping it up and down. Before every adjustment it re-detects the radio's volume the same way it does at startup; if the output no longer matches what the app last set, that reading becomes the radio's new volume and the boost is put back on top immediately — so turning down to 9 while boosted by 1 gives an effective 10
- **Changed:** Log lines now read `Manual volume change: stream X -> Y` and `Volume set: radio R + boost B = T`, and the heartbeat shows stream, radio volume and boost
- **Known limit:** a knob change to exactly the value the output is already at can't be seen, because Android doesn't report a change that doesn't change the value — see [Volume arithmetic](#volume-arithmetic)
- **Download:** [SpeedVolume-2.3-debug.apk](https://github.com/djbooya/SpeedVolume/raw/main/app/build/outputs/apk/debug/SpeedVolume-2.3-debug.apk)

### v2.2 (Oct 12, 2026)
- **Added:** Debug mode now also toasts when you change the volume yourself — e.g. turning down one step while boosted shows `Effective volume: 10 (radio 9 + boost 1)`. The service listens for Android's volume-change broadcast and tracks the volume it last set, so its own boost adjustments are not mistaken for manual ones
- **Added:** Manual volume changes are written to the log (`Manual volume change: 11 -> 10 ...`) whether or not debug mode is on
- **Download:** [SpeedVolume-2.2-debug.apk](https://github.com/djbooya/SpeedVolume/raw/main/app/build/outputs/apk/debug/SpeedVolume-2.2-debug.apk)

### v2.1 (Oct 12, 2026)
- **Added:** Debug mode, toggled from a new **⋮** menu at the top of the app. When on, a toast shows the effective volume every time a boost applies or is removed — e.g. `Effective volume: 11 (radio 10 + boost 1)`. Needed because the head unit's on-screen volume doesn't change when a boost applies. When off, no volume toasts appear. See [Debug Mode](#debug-mode)
- **Note:** Debug mode applies immediately instead of going through Save, because Save restarts the service and a restart disarms every tier mid-drive
- **Download:** [SpeedVolume-2.1-debug.apk](https://github.com/djbooya/SpeedVolume/raw/main/app/build/outputs/apk/debug/SpeedVolume-2.1-debug.apk)

### v2.0 (Sep 7, 2026)
- **Added:** Four boost tiers, up from two. All four are independently configurable and stack; tiers 3 and 4 default to disabled. Existing saved settings for tiers 1 and 2 carry over untouched
- **Added:** Tier **arming** — a tier can only engage after the app has seen the vehicle below that tier's threshold. Restarting the service mid-drive no longer boosts on the first GPS fix; the tier waits for a genuine crossing (drop below the threshold, then cross it again)
- **Fixed:** A boost is now only subtracted if it was actually applied. Previously, if the volume was already at maximum when a tier engaged, the clamped boost was still recorded as applied and subtracted on disengage — dragging the volume below where the driver had set it. The service now tracks the steps the stream actually moved
- **Changed:** Tier ordering warning now covers all four tiers, and per-tier state (armed / engaged / applied boost) is included in the heartbeat log for diagnosis
- **Download:** [SpeedVolume-2.0-debug.apk](https://github.com/djbooya/SpeedVolume/raw/main/app/build/outputs/apk/debug/SpeedVolume-2.0-debug.apk)

### v1.9 (Sep 1, 2026)
- **Root cause found:** Log analysis proved the head unit **force-stops** the package on sleep, not merely kills it. A force-stop deletes all pending alarms and blocks all manifest receivers, so none of the five self-restart mechanisms (boot/screen-wake/connectivity/package-update receivers, restart alarm) can ever fire. The decisive evidence: an exact alarm armed 2.7s before death, with battery optimization already exempted, silently vanished — and across 2,881 log lines `SCREEN_ON`, `CONNECTIVITY_CHANGE`, `MY_PACKAGE_REPLACED` and `BootReceiver` fired **zero** times, while every single recovery was a manual app open. This is unrecoverable from inside the app by design
- **Changed:** `SpeedVolumeService` is now `exported="true"` so an external watchdog can restart it — an explicit component start from another app is the only thing that clears the force-stopped state. See [Running under Automate](#running-under-automate) for setup
- **Fixed:** `onDestroy()` cancelled the restart alarm unconditionally, throwing away the app's own recovery path whenever the system stopped the service. It also meant `onTaskRemoved()`'s 5-second quick-restart alarm was cancelled milliseconds after being armed, since `onTaskRemoved` runs immediately before `onDestroy`. The alarm is now only cancelled when the user has actually switched the service off
- **Fixed:** `onStartCommand` logged the master switch but never honoured it, so a stale or external start would run the service even with the app switched off. It now stops immediately — important now that the service is externally startable
- **Download:** [SpeedVolume-1.9-debug.apk](https://github.com/djbooya/SpeedVolume/raw/main/app/build/outputs/apk/debug/SpeedVolume-1.9-debug.apk)

### v1.8 (Sep 1, 2026)
- **Added:** A partial CPU wake lock (`WAKE_LOCK` permission), held for the entire time the service runs and released in `onDestroy()`. Adopted after researching LlamaLab's Automate app (llamalab.com/automate), whose "Device keep awake" block uses the same technique - instead of *recovering* after Doze suspends the process, this *prevents* Doze from ever suspending it while the service is alive. The head unit is on constant power, so the usual phone-battery tradeoff doesn't apply
- **Removed:** The dynamic in-process `ScreenReceiver` (and its `onScreenOn()` hook in `SpeedVolumeService`) - it existed to re-request GPS updates after the CPU went to sleep and woke back up, which the wake lock now prevents from happening in the first place. Its job is also covered as a fallback by the existing 60-second periodic location-update check. The manifest-registered `ScreenWakeReceiver` (which restarts the whole service, not just GPS updates, and survives process death) is unaffected and still active
- **Download:** [SpeedVolume-1.8-debug.apk](https://github.com/djbooya/SpeedVolume/raw/main/app/build/outputs/apk/debug/SpeedVolume-1.8-debug.apk)

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
- Make sure "Allow exact alarms" and "Disable battery optimization" are both granted
- Check your head unit's manufacturer-specific "auto-start"/"protected apps" manager, if it has one — many aftermarket ROMs kill background apps outside standard Android battery optimization
- **If the service only ever comes back when you open the app by hand,** your unit is force-stopping the package. No in-app mechanism can recover from that — see [Running under Automate](#running-under-automate) for the external-watchdog fix, and for the ADB command that confirms the diagnosis

**Volume boost not applying**
- Ensure the service is actually running (check persistent notification)
- Check that location fix is acquired (notification should show speed and unit when active)
- Verify the speed threshold and hold time are appropriate for your driving

**Manual volume adjustments get overwritten**
- The app re-detects the radio's volume before every adjustment, so a manual change is kept — see [Volume arithmetic](#volume-arithmetic)
- Each knob change appears in the log as `Manual volume change: stream X -> Y`. If a knob change has no such line, Android never reported it (see the note under Volume arithmetic)
- `Volume set: radio R + boost B = T (applied=A)` lines show every adjustment; an `applied` smaller than the boost means the output hit its maximum

## Building from Source

```bash
cd SpeedVolume
export JAVA_HOME="/path/to/Android/Studio/jbr"
./gradlew assembleDebug
```

Output APK: `app/build/outputs/apk/debug/SpeedVolume-2.3-debug.apk`

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
