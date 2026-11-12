<p align="center"><img src="docs/logo.svg" width="128" alt="HermesSIM host logo: a yellow cat holding a phone"></p>

# HermesSIM

**Turn an old Android phone into a SIM box for your phone and your Mac.** Put your SIM in a spare rooted Android phone, leave it in a drawer, and get its calls, texts and mobile data on your Android phone or iPhone over Bluetooth, or over the internet when you are away. A Mac can ask the same phone for its hotspot.

This repository is the **host side** (the Android app, its root daemon and Magisk module), published under the GNU GPL v3. The HermesSIM apps for Android, iPhone and Mac are distributed separately.

## Features

### Calls

- **Calls on your phone.** Incoming calls ring on the client phone and are answered, made and talked on there. The host relays the call audio digitally (Opus) over Bluetooth LE, or over a TURN relay in internet mode.
- **A silent host.** The host phone never rings or plays a sound: a non-UI `InCallService` runs the call, and the module sets total silence, no haptics and zero ring, notification and system volume.
- **Call audio per phone model.** Each supported phone has an audio profile (`profiles/`) that the root daemon applies at call start and restores at call end.
- **Call test.** Call audio switches on only after a short test call with a friend passes on the client. Until then the host runs in Basic mode: texts, call control and the hotspot.
- **Call rules.** Up to 50 blocked numbers (optionally all hidden numbers): their calls are rejected and their texts stay on the host. Busy reply answers a text with your own message, at most once an hour per number.

### Texts

- Texts to the host's SIMs reach the client, with delivery states; replies go out on the line the text came in on.
- One-time codes are offered to the client's keyboard and, with permission, shown on the Mac.

### SIMs and the network

- **Dual SIM.** Each SIM can be turned on or off, and one carries mobile data and the hotspot.
- **Network mode per SIM.** Automatic, up to 4G or up to 3G, from the client. The networks below the chosen one stay on, so the line still takes calls where the higher one is missing. A change is refused during a call.
- **SIM unlock.** PIN and PUK from the client; the client can keep a PIN to unlock the host after a restart.
- **USSD codes.** Balance and package codes such as `*123#` run on a host line.
- **Reconnect and restart.** Airplane mode for a few seconds so the line registers again, or a restart of the host; both are refused during a call.

### Hotspot

- **Switched from the client.** On or off, name, password, band, security, hidden name, and off once nothing uses it.
- **Devices.** The devices on the hotspot with their data; a blocked device (up to 32) stays connected without internet.
- **Monthly data limit.** A notice at 80 %, and at the limit the hotspot turns off once.
- **Hotspot guard.** Two minutes after the client leaves Bluetooth range, the host turns its hotspot off if nothing uses it.
- **Hotspot during calls.** On a host whose hotspot can use only 2.4 GHz, the hotspot can pause while a call's audio runs over Bluetooth.
- **Mac Personal Hotspot mark.** On Android 13 and later, the hotspot can carry Apple's Personal Hotspot mark, so a Mac treats it as metered.
- **Wi-Fi when you are away.** Two minutes after the client leaves Bluetooth range, the host can join a saved network instead of using mobile data.

### Clients

- **One client phone.** A host pairs with one Android phone or iPhone over Bluetooth LE (GATT, plus an L2CAP channel for call audio); the phone proves the 6-digit code the host shows. They speak HermesSIM protocol 2: every request gets a reply, only the changed parts of the state travel, and a dropped session resumes where it stopped.
- **Internet mode.** When the client is out of Bluetooth range, the two phones talk through the HermesSIM server. Every message is sealed end to end, so the server never sees calls or texts. The host wakes the client by push for a call, a text, a missed call or a notice.
- **Up to 4 Macs.** Each Mac has its own Bluetooth service and permissions: the hotspot always, texts and codes and host settings only when the client allows them.

### Box mode and battery

- **Box mode.** Screen and touch off, no sounds or wake-ups, battery savers on. Volume Up three times, then Volume Down three times, gives the screen back for 10 minutes.
- **Charge limit.** The battery is held at 60 % or 80 % (100 % turns it off) through the kernel's standard power supply controls. See [host/README.md](host/README.md#battery-care).
- **Host notices.** The client is told about a low or critical battery, a locked SIM and the hotspot data limit.

### Updates and support

- **Updates.** The host checks for a new version every day. By default it downloads and installs one only when the client says so. With *Install updates automatically* turned on in the client, it downloads new versions by itself and installs them between 02:00 and 05:00 when no call is up.
- **Compatibility check.** Before root, the host app checks the phone against the support table.
- **Diagnostics.** The client can ask for the host's recent log with personal data scrubbed.
- **Giving the phone back.** *Stop using this phone as a host* restores its settings, sounds and default apps, removes HermesSIM and restarts the phone (Magisk stays).

## Which phones work?

See the **[support page](https://kilimcininkoroglu.github.io/hermessim/)** (also in the host app before you root, and in the client apps under Host → Check a phone). Tiers: *verified* (measured by us), *expected* (same audio platform as a verified phone), *experimental* (researched, not measured), *unknown*. Whatever the tier, call audio switches on only after a short **call test** on your phone; until then the host runs in Basic mode (texts, call control, hotspot).

Requirements: Android 11 or newer, an unlockable bootloader, Magisk, Bluetooth LE peripheral support. Unlocking the bootloader erases the phone and may void its warranty. HermesSIM never changes the phone's IMEI.

## Setting up a host

1. Check your phone on the [support page](https://kilimcininkoroglu.github.io/hermessim/), then unlock the bootloader and install [Magisk](https://github.com/topjohnwu/Magisk) (see its guide for your phone).
2. On the phone, download `HermesSIM-Host-<version>-Magisk.zip` from the [Releases page](https://github.com/KilimcininKorOglu/hermessim/releases).
3. Open Magisk → *Modules* → *Install from storage* → choose the zip → *Reboot*. HermesSIM Host starts by itself after the restart. There is no separate app to install: Google Play Protect blocks it on many phones, so the Magisk module carries it.
4. Pair from the HermesSIM app on your phone (*Add host*), then run *Host → Call audio → Run the call test* with a friend.
5. Turn on *Box mode* and put the phone away. To use its screen again: Volume Up ×3, then Volume Down ×3.
6. To give the phone back: on its screen, *Stop using this phone as a host*. Its settings, sounds and default apps come back, HermesSIM removes itself and the phone restarts (Magisk stays).

## Building from source

- Host app, daemon and module: `host/release.sh` (needs the Android SDK and NDK and your own signing key in `host/signing/` as `release.jks` and `signing.properties`, gitignored, or the `HERMESSIM_SIGNING_*` environment variables), or the developer loop in [host/README.md](host/README.md).
- Releases: push a tag `host-v<versionName>` (for example `host-v1.0.0`). The [Release host](.github/workflows/release.yml) workflow builds the signed Magisk zip and publishes the GitHub release. It needs the repository secrets `HERMESSIM_SIGNING_KEYSTORE_B64` (base64 of `release.jks`), `HERMESSIM_SIGNING_STORE_PASSWORD`, `HERMESSIM_SIGNING_KEY_ALIAS` and `HERMESSIM_SIGNING_KEY_PASSWORD`.
- Protocols shared with the client apps: [protocol/](protocol/). Protocol 2 for the phones is specified in [protocol/v2/SPEC.md](protocol/v2/SPEC.md); the Mac service keeps the messages of `protocol/hermessim.proto`.
- Device profiles and the support table: [profiles/](profiles/). Measuring a new phone: [tools/](tools/) (read-only dump and the four live-call experiments).

## Acknowledgements

The HermesSIM host is built on the original work of [Mert Eren Karabulut](https://github.com/Mert-Eren-Karabulut). Thank you for creating and publishing it: [github.com/Mert-Eren-Karabulut/tosbisim](https://github.com/Mert-Eren-Karabulut/tosbisim).

## Licence

Host: GPL-3.0-or-later ([LICENSE](LICENSE)). Protocol 2: GPL-3.0-or-later or the HermesSIM proprietary licence ([protocol/v2/LICENSE](protocol/v2/LICENSE)). Mac service messages: MIT ([protocol/LICENSE](protocol/LICENSE)).
