# hermessimd-rs — HermesSIM host daemon in Rust

A from-scratch Rust implementation of the Android root daemon that owns the host side of the
HermesSIM: ALSA call routing, Opus audio over the BLE/L2CAP or TURN bridge, box mode, battery
care, network blocking, the key escape gesture and the persistent log capture.

The wire behaviour is intentionally identical to the C daemon in `host/daemon/`:

- control socket: abstract `@hermessimd`, one line per request, same commands and replies;
- audio socket: abstract `@hermessimd-audio`, `SOCK_SEQPACKET`, `[u16 seq LE][u8 flags][opus]`;
- same file paths under `/data/adb/hermessim`, same sysfs charge nodes, same iptables chain;
- same `hermessimd` logcat tag and `DAEMON_UP` / `BOX_ESCAPE` broadcasts;
- same safe-mode/crash-breadcrumb behaviour and exit codes (3 sound card gone, 10 update).

## Layout

| Path | Purpose |
| --- | --- |
| `src/main.rs` | CLI, signals, control socket and main loop |
| `src/alsa.rs` | kernel ALSA control/PCM ABI for aarch64 and armv7 |
| `src/mixer.rs` | managed controls, call routing, safe mode, call originals, inventory |
| `src/audio.rs` | Opus pump, jitter buffer, capture/playback, audio client socket |
| `src/box.rs` | box mode, battery care, netblock, keys, uevent, log capture |
| `src/protocol.rs` | line protocol, reply queue, command parsing |
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
`*-linux-android29-clang` driver and requests 16 KB segment alignment, matching the C module.

Host-only unit tests (`cargo test`) cover the protocol helpers, jitter buffer, charge band,
netblock command builder and escape gesture; the Android-only modules are compiled by
`cargo check --target aarch64-linux-android`.

## Licence

GPL-3.0-or-later. The original C daemon and this reimplementation are free software; see
`LICENSE`. The vendored libopus is BSD-licensed, see `vendor/opus/COPYING`.
