# MLCS PowerLift Pro — serial protocol

Recovered by decompiling `powerliftpro-3.0.1.apk` (package `com.powerlift`).
Confirmed on a real lift (firmware 2.0.0): connecting, `W`, `V`, `SS`, `P`, `O`, `Z`, `S:`, `R:`, `X:`, `A:`, `T:`, `E:` and the `^SS`, `^L`, `^V` replies. The rest is read from the app's code only. See [Observed behaviour](#observed-behaviour-firmware-200) for what testing showed.

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
| `MOTORWAKE` | Reply to `W` (no `^` prefix; not handled by the vendor app) |

## App-side behaviour worth copying

- On connect: `W`, `V`, `SS`, `P`.
- Units: default **1705 steps per inch**; calibration setting offsets this by 0, ±5, ±15 or ±20.
- The lift does not persist position: the app saves the last position and restores it with `E:<steps>`.
- Anti-backlash (optional setting, in steps): on a direction change, the app first sends `E:` with the
  current position shifted by the backlash value against the new direction, waits 50 ms, then sends the move
  (for `A:` it also adds the backlash value to the step count).
- Presets, named memory groups and sequences are stored on the phone, not the lift (only `M:` positions are pushed).

## Observed behaviour (firmware 2.0.0)

Measured on one lift with the app's message log.

- **Moves are reported unprompted.** Any move, including one from the foot pedal, sends `^SS` at start and stop and a `^L` position about every 60 steps while moving.
- **The lift counts on its own.** Pedal moves made with no phone connected are included in the position it reports on the next connect.
- **Position is lost at power-off.** After a power cycle the lift reports `^L000000`. `E:<steps>` restores a remembered value.
- **Limits.** The lift stops itself at either limit and sets the limit flag. Travel between limits measured 5797 steps (about 3.40 in). Reaching a limit does not reset the count. Repeated approaches to the upper limit tripped within a 6-step spread.
- **Moves do not need the link.** `X:` keeps running to the limit after the phone disconnects. `R:` (jog) also keeps running if the link drops before `O` is sent. Nothing in the lift acts as a dead-man switch.
- **Speed.** Measured about 60 steps per second at `S:30`, 205 at `S:89`, 216 at `S:90` and 225 at `S:130`. The foot pedal runs at about 200.
- **Scale.** A 426-step move measured 0.2495 in on a dial indicator, about 1707 steps per inch.
- **Backlash.** After a change of direction the first 0.010 to 0.015 in of commanded travel does not move the carriage. From the same direction a position repeats within about 0.002 in.
- **The count drifts.** The upper limit switch trips at the same physical height within 0.0015 in, but the count the lift reports there rises with use: about 1 step per down-and-up cycle at `S:30` and 2 to 3 at `S:90`. Over one session it moved about 60 steps (0.035 in). The limit switch is therefore a better reference than the count.
- **The lower limit is a repeatable reference.** Running to the lower limit and then moving up 3 in (`A:5115`) landed within 0.001 in on a dial indicator over five tries, while the reported count shifted by about 5 steps over the same tries.
