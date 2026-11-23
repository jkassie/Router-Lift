# MLCS PowerLift Pro — serial protocol

Recovered by decompiling `powerliftpro-3.0.1.apk` (package `com.powerlift`).
Connecting, position and firmware replies, jog (`R:`) and stop (`O`) are confirmed on a real lift (firmware 2.0.0). The rest is read from the app's code only.

## Transport

- **Bluetooth Classic, Serial Port Profile (RFCOMM)**, UUID `00001101-0000-1000-8000-00805F9B34FB`. Not BLE.
- The lift must already be paired; the app picks the bonded device named `mlcs-powerlift-pro` (case-insensitive).
- The app also supports a USB serial connection: 115200 baud, 8 data bits, 1 stop bit, no parity, no flow control.
- No authentication, handshake, checksum or encryption. Plain ASCII lines ending in `\n`.

## Commands (app → lift)

| Command | Meaning |
|---|---|
| `W` | Wake motor from sleep |
| `Q` | Motor sleep (named in the app, never sent by it) |
| `V` | Request firmware version |
| `SS` | Request switch state |
| `P` | Request current position |
| `O` | Stop lift |
| `C` | Cancel running operation |
| `Z` | Zero the position at the current height |
| `S:<speed>` | Set speed |
| `R:<dir>&S:<speed>` | Jog while held: dir `1` = up, `0` = down. App sends on press, `O` on release |
| `X:<dir>&S:<speed>` | Run to max up (`1`) / max down (`0`) |
| `A:<steps>` | Move relative by signed step count |
| `T:<steps>&S:<speed>` | Travel to absolute step position |
| `E:<steps>` | Tell the lift its current position is `<steps>` (no movement) |
| `M:<p1>,<p2>,...` | Send memory-bank positions (steps, comma separated) |

Speed is the slider value 1–99; the app sends **130** for "100%" / turbo.

## Responses (lift → app)

Lines start with `^`.

| Response | Meaning |
|---|---|
| `^SSabcd` | Switch state; each flag is active when `0`. a = upper limit reached, b = lower limit reached, c = running up, d = running down. `^SS1111` = idle. Sent unprompted when the foot pedal is used |
| `^L<6 chars>` | Current position in steps (line is exactly 8 chars); otherwise app re-requests with `P` |
| `^MB<6 chars>` | A memory-bank position was reached (steps) |
| `^V<text>` | Firmware version |
| `^E<3 digits>` | Error. `^E099` = no power to the motor |

## App-side behaviour worth copying

- On connect: `W`, `V`, `SS`, `P`.
- Units: default **1705 steps per inch**; calibration setting offsets this by 0, ±5, ±15 or ±20.
- The lift does not persist position: the app saves the last position and restores it with `E:<steps>`.
- Anti-backlash (optional setting, in steps): on a direction change, the app first sends `E:` with the
  current position shifted by the backlash value against the new direction, waits 50 ms, then sends the move
  (for `A:` it also adds the backlash value to the step count).
- Presets, named memory groups and sequences are stored on the phone, not the lift (only `M:` positions are pushed).
