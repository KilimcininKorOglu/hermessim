# hermessimd: HermesSIM host daemon

The Android root daemon, written in Rust, that owns the host side of the
HermesSIM: ALSA call routing, Opus audio over the BLE/L2CAP or TURN bridge, box mode, battery
care, network blocking, the key escape gesture and the persistent log capture.

It replaced the earlier C daemon with the same behaviour:

- control socket: abstract `@hermessimd`, one line per request, same commands and replies;
- audio socket: abstract `@hermessimd-audio`, `SOCK_SEQPACKET`, `[u16 tick LE][opus]` (a tick is 10 ms);
- same file paths under `/data/adb/hermessim`, same sysfs charge nodes, same iptables chain;
- same `hermessimd` logcat tag and `DAEMON_UP` / `BOX_ESCAPE` broadcasts;
- same safe-mode/crash-breadcrumb behaviour and exit codes (3 sound card gone, 10 update).

The Magisk module carries this daemon (`build.sh` output in `out/<abi>/hermessimd`).

## Features

The daemon is the only root process of the host. The host app drives it over the control socket.

- **Call routing.** `CALL_START` applies the phone's audio profile (capture route, in-call music mixer, device mutes) for voice session 1 or 2; `CALL_END` restores every control it touched. When the vendor audio HAL rewrites a managed control during a call, the daemon writes it back (ALSA control events).
- **Safe mode.** A breadcrumb file is kept while audio controls are being written. When the previous boot ended abnormally during such a write, the daemon writes no mixer control for the whole boot, so a control that froze the phone cannot do it again. When a previous run of the same boot died during a call, the next start restores the controls saved at call start.
- **Call audio bridge.** On `@hermessimd-audio` the daemon captures the call's uplink, encodes it with Opus in 10 ms packets (Bluetooth) or 20 ms packets (internet, `FRAME`) and sends it to the host app. It plays the client's packets of any length into the call by their tick, with forward error correction over gaps and packet loss concealment (the shared playout rule, `src/playout.rs`). `LOSS` sets the encoder's expected loss from the phone's report, `TUNE` adjusts the playout live, and `AUDIO?` reports this call's counters for the call test.
- **Mixer inventory.** `INVENTORY` writes every ALSA control of the sound card to a file, and `HAS` checks one control, for building new phone profiles.
- **Box mode.** Screen off and wake, the default app roles (`ROLE`, through `cmd role add-role-holder`), and the key escape gesture: Volume Up three times, then Volume Down three times, wakes the screen.
- **Battery care.** `CHARGE` holds the battery at a target through the kernel's standard `power_supply` nodes (`charge_behaviour`, a cap node, a cut node). Power supply uevents re-check the band; every control is released at start, exit, below 15 % and when the limit is off. `CHARGE?` reports the state and whether the phone keeps undoing the limit.
- **Hotspot data and devices.** `IFSTATS` reads an interface's byte counters and `NEIGH` lists the hotspot's devices from the kernel ARP table.
- **Network blocking.** `NETBLOCK` cuts one app uid off the network through the iptables chain `hermessim_block`.
- **Network cycle.** `NETCYCLE` turns airplane mode on for 3 to 30 seconds and then off, from a detached script, so the line registers again even if the app or the daemon stops in between.
- **Log capture.** A logcat process writes the host's log to `/data/adb/hermessim/logs/host.log`; after a restart it continues after the last whole line.
- **Broadcasts.** `DAEMON_UP` when the daemon is ready and `BOX_ESCAPE` when the escape gesture is used.

## Control commands

One newline-terminated line per request on `@hermessimd`; the reply is one `OK ...` or `ERR ...`
line. The client must be root or the uid given with `--uid` (checked with `SO_PEERCRED`).

| Command | Reply |
| --- | --- |
| `HELLO` | `OK hermessimd <version> card= call= session=` |
| `STATUS` | `OK call= session= managed= silence= safe= <names>` |
| `SET <key> <value>` | `OK` / `ERR bad SET`; keys `capture`, `rec_config`, `inject.1`, `inject.2`, `mute.tx`, `mute.rx`, `mute.vsid`, `mute.ramp` |
| `CALL_START <session>` | `OK applied=<n>`; applies the profile's routes for voice session 1 or 2 |
| `CALL_END` | `OK`; restores every touched control and clears both device mutes |
| `IFSTATS <iface>` | `OK rx=<bytes> tx=<bytes>` (hotspot data counter) |
| `NEIGH <iface>` | `OK <n> <mac>,<ip>;...` (hotspot devices from the kernel ARP table) |
| `HAS <control>` | `OK count=<n> [items=a\|b\|c]` / `ERR no such control` |
| `INVENTORY` | `OK <n> controls in /data/adb/hermessim/inventory.txt` |
| `TUNE [prime keep period]` | `OK prime= keep= period=` (playout ticks, inject PCM period; live) |
| `FRAME 10\|20` | `OK` / `ERR bad value` (length of the packets to the audio client) |
| `LOSS <0..100>` | `OK` / `ERR bad value` (the encoder's expected loss) |
| `AUDIO?` | `OK call= session= silence= cap= voiced= sent= played= peak= txdrop= noclient= recv= plc= starved= live= capms= caperr= fec= late=` (this call's totals) |
| `SCREEN_OFF`, `WAKE` | `OK` / `ERR spawn failed` |
| `POWERKEY 1\|0` | `OK <devices>` / `ERR no escape gesture on this phone` |
| `ROLE <role> <pkg>` | `OK` / `ERR bad role or package` |
| `NETBLOCK <uid> <0\|1>` | `OK` / `ERR bad uid` (iptables chain `hermessim_block`) |
| `NETCYCLE <seconds>` | `OK` / `ERR busy` / `ERR failed` (airplane mode on for 3-30 s, then off, from a detached script) |
| `CHARGE <target>` | `OK` / `ERR unsupported` / `ERR failed` |
| `CHARGE?` | `OK <node> target= resume= drain= cutting= held=0\|1 stuck=0\|1` / `ERR unsupported` |
| `STALL <ms>` | `OK` (debug: stalls the playback thread once) |

Debug client: `hermessimd --client "CMD|SLEEP n|CMD..."` sends the commands over one connection
and prints each reply.

## Layout

| Path | Purpose |
| --- | --- |
| `src/main.rs` | CLI, signals, control socket, command handlers and main loop |
| `src/alsa.rs` | kernel ALSA control/PCM ABI for aarch64 and armv7 |
| `src/mixer.rs` | managed controls, call routing, safe mode, call originals, inventory |
| `src/audio.rs` | Opus pump, capture/playback, audio client socket |
| `src/playout.rs` | the playout rule, with host-side tests against `vectors/playout.txt` (a copy of the protocol's vector) |
| `src/boxmode.rs` | box mode, battery care, netblock, keys, uevent, log capture |
| `src/protocol.rs` | pure helpers with host-side tests: C number parsing, reply and log line formats, scripts, validation |
| `src/opus.rs` | libopus FFI |
| `src/platform.rs` | Android logging and small libc helpers |
| `vendor/opus` | vendored libopus 1.5.2 (BSD), built by `build.rs` |

## Build

Requirements: Rust, the Android NDK (27.x used by the host module), CMake and a C compiler
from the NDK.

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi
export ANDROID_NDK_HOME=/opt/homebrew/share/android-commandlinetools/ndk/27.1.12297006
export PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH"

cargo build --release --target aarch64-linux-android
cargo build --release --target armv7-linux-androideabi
```

The binaries are `target/<target>/release/hermessimd`. The Cargo config links with the NDK
`*-linux-android29-clang` driver and requests 16 KB segment alignment, which Android 15 and later need on 16 KB-page kernels.
`./build.sh` builds both ABIs and copies them to `out/arm64-v8a/hermessimd` and
`out/armeabi-v7a/hermessimd`, verifying that every loadable segment is 16 KB-aligned.

Host-only unit tests (`cargo test`) cover the playout rule in `src/playout.rs` and the pure helpers in `src/protocol.rs`: strtoll/atoi
parsing, command arguments, the `AUDIO?`, `CHARGE?` and 5 s audio line formats, the charge band,
the netblock and network cycle scripts, enum, call-originals and ARP parsing, the capture clock,
the PCM rate fallback and the escape gesture. Every other module is Android-only and is compiled
by the clippy check below.

Lint gate (both Android targets, no warnings allowed):

```sh
cargo clippy --release --target aarch64-linux-android --all-targets -- -D warnings
cargo clippy --release --target armv7-linux-androideabi --all-targets -- -D warnings
```

`Cargo.toml` and `clippy.toml` make a cognitive complexity above 10 and any `unwrap` or `expect`
outside tests a build error.

## Licence

GPL-3.0-or-later, see `LICENSE`. The vendored libopus is BSD-licensed, see `vendor/opus/COPYING`.
