# Router Lift

An open-source Android app for controlling the **MLCS PowerLift Pro** motorised router lift over Bluetooth.

The vendor's app is no longer available in the app store. This project is an independent replacement, written from scratch, so the lift keeps working on current phones.

> **Unofficial.** This project is not affiliated with, endorsed by, or supported by MLCS. "MLCS" and "PowerLift Pro" are names of their respective owner and are used here only to say what hardware the app works with.

## Safety

This software moves a motor next to a spinning cutter.

- Use it at your own risk. It comes with no warranty (see [LICENSE](LICENSE)).
- Keep the lift's foot pedal and power switch within reach whenever the app is connected.
- **The lift does not stop if the Bluetooth link drops mid-move.** Tested: a move in progress, including a held jog, carries on until a limit switch. Never rely on the app as the only way to stop it.
- Never adjust height with your hands near the bit.

## Status

Early, but working on real hardware (Pixel 11, Android 17, lift firmware 2.0.0).

| Feature | State |
|---|---|
| Connect to a paired lift | Tested |
| Live height readout, inches or millimetres | Tested |
| Hold-to-jog up / down, stop | Tested |
| Go to height, move by amount, run to max up / down, set zero, speed | Tested |
| Restore height after a power cycle | Tested |
| Nudge by a preset amount (editable list, inches or millimetres) | Implemented, not yet tested on the lift |
| Saved positions, with go at slider speed or full speed | Implemented, not yet tested on the lift |
| Undo for "set zero" | Implemented, not yet tested on the lift |
| Sequences | Not implemented yet |
| Calibration (steps per inch) and anti-backlash | Not implemented yet |

## Using it

Requires Android 8.0 or newer. iPhones cannot be supported: the lift uses Bluetooth Classic serial, which iOS does not allow third-party apps to open.

1. Build the app (below) and install the APK on your phone.
2. Power the lift on and pair it in Android's Bluetooth settings. It appears as `MLCS-PowerLift-Pro`.
3. Open Router Lift, tap **Connect**, and allow the "Nearby devices" permission.

The app saves the last height on the phone. If the lift reports exactly 0 on connect while the phone remembers a different height, the app assumes the lift was power-cycled and sends the remembered height back; otherwise the lift's own reading is used.

## Building

You need JDK 17 and the Android SDK (platform 35, build-tools 35). With Android Studio, open the `android/` folder. From the command line:

```bash
cd android
./gradlew assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. If Gradle cannot find the SDK, set `ANDROID_HOME` or create `android/local.properties` with a `sdk.dir=` line.

Run the unit tests with `./gradlew testDebugUnitTest`.

## How the lift is controlled

The lift speaks a simple ASCII line protocol over Bluetooth Classic serial (SPP), for example `R:1&S:50` to jog up at 50% speed and `O` to stop. The full command and reply tables are in [PROTOCOL.md](PROTOCOL.md). The same protocol should work for anything else that can open a serial link to the lift, such as a desktop script.

The protocol was worked out by studying the vendor's Android app. Neither that app nor its decompiled code is included here, and they should not be added. If you have your own copy of the APK and want to check the analysis, [tools/decompile_apk.py](tools/decompile_apk.py) reproduces it:

```bash
python3 -m venv .venv && .venv/bin/pip install androguard
.venv/bin/python tools/decompile_apk.py powerliftpro-3.0.1.apk decompiled/
```

## Layout

- `android/` — the app (Kotlin, Jetpack Compose).
  - `lift/LiftProtocol.kt` — commands, reply parsing, unit conversion. Pure Kotlin, unit tested.
  - `lift/LiftLink.kt` — the Bluetooth serial connection.
  - `LiftViewModel.kt` — app logic and state.
  - `ui/LiftScreen.kt` — the control screen.
- `PROTOCOL.md` — the lift's serial protocol.
- `tools/` — the decompile helper.

## Contributing

Issues and pull requests are welcome, especially reports from other firmware versions and phones. Please do not attach the vendor's APK or decompiled source.

## Licence

[MIT](LICENSE).
