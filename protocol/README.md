# HermesSIM protocol v1 (MIT)

Shared by the HermesSIM host (GPL-3.0) and the HermesSIM clients (proprietary).
Schema: `hermessim.proto`. Lessons it encodes come from the M1 spike (`spikes/m1/RESULTS.md`).

## Transport
Host = BLE peripheral (advertises the service UUID), client = central.

| UUID | Role | Properties / security |
| --- | --- | --- |
| `0e9c0001-0971-48af-ba0b-2c5c254faec0` | primary service | advertised |
| `0e9c0002-0971-48af-ba0b-2c5c254faec0` | `CTRL_TX` host -> client | notify, encrypted (bonding required) |
| `0e9c0003-0971-48af-ba0b-2c5c254faec0` | `CTRL_RX` client -> host | write with response, encrypted |
| `0e9c0004-0971-48af-ba0b-2c5c254faec0` | `AUDIO_PSM` | read, encrypted: u16 LE L2CAP PSM (M5) |

## Framing
Each direction of the control channel is an ordered, reliable byte stream (ATT notifications /
write-with-response preserve order). Concatenate the payloads and split into frames:
`[u16 little-endian length][Envelope protobuf bytes]`, max frame 4096 bytes. A frame may span several
notifications or writes (MTU is typically 185-517 on iOS, 517 on Android). Reset both stream buffers on reconnect.

Call audio (L2CAP CoC on `AUDIO_PSM`) is a byte stream of `[u16 LE length][u16 seq LE][u8 flags][opus]` packets,
10 ms each. A sender puts every packet waiting into one write (one L2CAP SDU on Android): a link that carries one SDU per
connection event can't take 100 single-packet writes a second. Receivers parse the stream, never one packet per read.

## Session rules
- After subscribing to `CTRL_TX`, the client sends `Hello`; the host answers `Hello` + `HostStatus`,
  then replays undelivered `SmsReceived` and the state of any ongoing call (`CallEvent`).
- Host sends `KeepAlive` every 60 s while idle; the client answers with `KeepAlive` (same `n`).
- `SmsReceived` is store-and-forward: the host keeps it until `SmsAck`, resending on each reconnect.
- The host only serves the bonded client whose `install_id` it pinned at pairing; anything else gets
  `Error{NOT_PAIRED}` and is disconnected.
- Host must request a short connection interval while a call is ringing/active (idle-relaxed links
  made a ring take 1.9 s in M1).
- Numbers: the host sends `number_raw` and, when possible, `number_e164` (normalised with the SIM's
  country) so the client can match the phone's Contacts list.
- Ringback (outgoing calls): with `alerting_known`, the client stays silent until `alerting`, then plays
  the network's early media, or its own ringback (the SIM country's ITU E.180 tone) if no network audio
  arrives within 1 s. Without it (older hosts, or a phone whose precise call state the host can't read),
  the local ringback starts with dialing. Network audio always silences the local ringback for the rest
  of the dialing phase. The same DIALING event may arrive again (replays): it changes nothing.
- USSD (where `HostStatus.ussd`): `UssdRequest` runs one code on a line and the host answers `UssdResult` with
  `reply_to`. One code runs at a time; a second one meanwhile gets `NETWORK`. A code that opens a menu returns the
  menu's text, but the menu cannot be answered: Android's API sends a single request.
- Call rules (where `HostStatus.call_rules` is set): `SetCallRules` replaces the blocked numbers, the hidden-number
  switch and the busy reply. Blocked calls and texts never reach a client, a push or a Mac. A text the host sends by
  itself (the busy reply, a Mac's reply) reaches the clients served at that moment as `HostSmsSent` (never as a
  push, and not replayed), then its outcome as `SmsSendResult` with the same id.
- Host actions (where `HostStatus.host_actions`): `HostAction{RECONNECT_NETWORK}` turns airplane mode on for 5 s, then
  off, from a detached root script, so airplane mode goes off even if the host app dies meanwhile; a hotspot that was on
  comes back on. `HostAction{REBOOT}` restarts the phone. The host answers `HostActionResult` with `reply_to` when it
  starts (a restart follows a second later) and refuses both during a call (`IN_CALL`). Every link drops meanwhile and
  comes back by itself.
- Hotspot devices: `Hotspot.Client` carries the device's IPv4 address and, where `capabilities.client_usage`, the bytes
  to and from it since the hotspot came on (counted per address in an iptables chain; a phone whose tethering runs in
  hardware offload may count less). Where `capabilities.block_clients`, `SetHotspotBlock` cuts a device off the
  internet by its MAC address: it stays connected, its forwarded traffic is dropped. The list (`Hotspot.blocked`, at
  most 32) is kept on the host and applied whenever the hotspot comes on.
- Network mode: `Sim.network_mode` is the highest network generation the user allows on a line (automatic, up to 4G,
  up to 3G); the generations below it stay allowed. `Sim.network_modes` lists the choices the line's modem offers (no
  "up to 4G" without a 5G radio); an empty list means the host can't set it. `SetSim.network_mode` takes one of them
  and is refused (`BAD_REQUEST`) during a call, because the modem registers again and the call could drop. The choice
  stays on the host's modem across restarts; box mode's 5G cut applies on top of it.
- Charge limit: `SetBox.charge_limit` takes 50-100 (the clients offer steps of 5; 100 = off). `Box.charge_state` says
  what the limit does now; `STUCK` means the phone kept undoing the cut and the host left it for the hour, and the
  host then sends `HostNotice{CHARGE_CONTROL_STUCK}` once.

## Remote mode (internet)

Used when Bluetooth is out of range and the user switched Remote mode on. Bluetooth always wins when both are up.

**Key.** The client makes 32 random bytes `K` and sends `RemoteSetup{enabled, key}` **over Bluetooth only** (the host
ignores it on the internet path). Derived keys, all HKDF-SHA256 with salt `"hermes-remote-v1"`, 32 bytes:

| info | use |
| --- | --- |
| `room-token` | `room_id = hex(sha256(room_token))`; the server checks this and nothing else |
| `control` | control messages |
| `audio` | call audio |

**Control.** Both phones join the Reverb (Pusher protocol 7) presence channel `presence-room.<room_id>`
(`GET /api/v1/remote` gives the WebSocket URL; `POST /api/v1/remote/auth {socket_id, channel_name, room_token
(hex), role: host|ios}` signs the subscription; member ids are `host` / `ios`; `ios` is the client phone, iPhone or
Android: a wire name from before the Android app). An envelope travels as client event
`client-m` with data `{"b": base64(nonce12 || ChaCha20-Poly1305(control key, nonce, plaintext))}`, plaintext =
`[u64 BE unix ms][Envelope bytes]`, AAD = `"h2c"` (host to client) or `"c2h"`. Receivers drop messages older than
120 s or whose nonce they have already seen. The session rules are the same as over Bluetooth (Hello first; the
host serves only the pinned install_id).

**Audio.** Each side takes a TURN allocation on the HermesSIM relay (`POST /api/v1/turn {install_id}` gives short-lived
REST credentials), the client offers its relay address in `RemoteAudio`, the host answers with its own, and each side
ChannelBinds (channel 0x4000) the other's relay address. One UDP datagram carries one audio packet:
`nonce12 || ChaCha20-Poly1305(audio key, nonce, [u16 seq LE][u8 flags][opus])`, AAD `"h2c"` / `"c2h"`, the same
inner packet as the L2CAP channel (10 ms frames; the receiver's jitter buffer reorders). Allocations are refreshed and
channels re-bound every 4 minutes while a call lasts; `RemoteAudio{close}` or the call ending releases them.

## Push

When the client can't hear the host (its system closed the app, or it is away from Bluetooth), the host has
hermessim.keremgok.tr push a sealed envelope: through APNs to an iPhone, through Firebase Cloud Messaging to an Android phone
(the server picks by what the room registered; the host sends the same request either way). Push has its own key `P`: the client makes 32 random bytes and sends
`PushSetup{key}` over Bluetooth only whenever the host's `HostStatus.push_key_id` (first 8 bytes of sha256(P)) isn't
its own; Internet mode can be off. A host that never reports `push_key_id` (older than 0.12.10) uses remote mode's
key K instead, and so does the client with it. Below, `room_token` and the push key are derived from P (or K) exactly
as remote mode derives them from K. The client registers `POST /api/v1/push/register {room_token,
voip_token, alert_token | fcm_token, environment: sandbox|production, app_version}` (stored under `room_id`; one phone
per room, registering replaces it; no tokens = forget)
and the host sends `POST /api/v1/push/send {room_token, kind: call|sms|note|notice, sealed: base64, collapse_id?}`.
`sealed` = `nonce12 || ChaCha20-Poly1305(push key, nonce, [u64 BE unix ms][Envelope])`, AAD `"h2c"`, push key = HKDF
info `push` (the client keeps only this key where its notification extension can read it). `PushSetup{}` (empty) or
Unpair forgets P.

| kind | when | APNs (iPhone) | FCM (Android) | client |
| --- | --- | --- | --- | --- |
| `call` | `CallEvent` RINGING, incoming, no client on Bluetooth, full voice | VoIP, expiration 0 | data `{k, s}`, high priority, TTL 60 s | iPhone: rings it in CallKit at once (every VoIP push must report a call); Android: rings it through Telecom; then reaches the host as usual |
| `sms` | `SmsReceived` not acked within 4 s | alert, mutable, collapse id = SMS id, 1 day | data `{k, s}`, high priority, TTL 1 day | iPhone: the notification extension writes the text's notification and the app stays quiet when the text arrives; Android: stored and shown as if the link brought it (deduplicated by id) |
| `note` | a ringing call in Basic mode | as `sms`, collapse id `basic-<call_id>` | as `sms` | "Missed call" note |
| `notice` | a `HostNotice`, no client on Bluetooth | as `sms`, collapse id `notice-<kind>-<slot>` | as `sms` | the notice as a notification |

**Health notices.** The host sends each `HostNotice` once per event: battery at 15 % and at 5 % while not charging
(armed again once it charges or passes 30 %), a SIM that newly asks for its PIN or PUK, the monthly hotspot limit
at 80 % and 100 %, a SIM that starts roaming (armed again once it stops; the host turns nothing off), and a charge
limit the phone keeps undoing (armed again once the limit holds). That the host has been unreachable for a while the client works out itself (a host that is off
can't send anything).

An Android app the user force-stopped gets no push until it is opened again (Android's rule); a swiped-away one does.

## Mac

A Mac asks the host for its hotspot on a GATT service of its own, and reads the recent texts where the client allowed
it. Its connections never count as the client's link. Every characteristic needs an encrypted link, and the host
answers only Macs on its list (`HostStatus.mac.macs`); any other central gets `GATT_INSUFFICIENT_AUTHORIZATION`. The
texts characteristic and its notifications answer only a Mac with `PairedMac.texts`; another gets the same error.

| UUID | Role | Properties / security |
| --- | --- | --- |
| `0e9c0011-0971-48af-ba0b-2c5c254faec0` | Mac service | advertised in a second advertising set, apart from the client's |
| `0e9c0012-0971-48af-ba0b-2c5c254faec0` | status: `MacStatus` | read, notify (no value when too long for the link: read again), encrypted |
| `0e9c0013-0971-48af-ba0b-2c5c254faec0` | command: `MacCommand` | write with response, encrypted |
| `0e9c0014-0971-48af-ba0b-2c5c254faec0` | secret: `MacSecret` | read, encrypted |
| `0e9c0015-0971-48af-ba0b-2c5c254faec0` | Mac pairing | advertised in place of the Mac service while the pairing window is open |
| `0e9c0016-0971-48af-ba0b-2c5c254faec0` | texts: `MacTexts` | read, notify (no value: read again), encrypted, only with `PairedMac.texts` |
| `0e9c0017-0971-48af-ba0b-2c5c254faec0` | reply: `MacReply` | write with response (prepared write allowed), encrypted, only with `PairedMac.texts` |
| `0e9c0018-0971-48af-ba0b-2c5c254faec0` | control: `MacControl` | write with response, encrypted, only with `PairedMac.control` |

Each value is one serialised message; a long read continues at its offset. **Pairing.** The client sends
`SetMacPairing{on}`; for 120 s the host advertises the pairing UUID and confirms the one Bluetooth pairing request
that arrives, then puts that Mac on its list. The Mac starts the pairing by reading an encrypted characteristic.
`RemoveMac` takes a Mac off the list and drops its bond. A host whose Bluetooth can't run two advertising sets reports
`supported = false`. **Texts.** `SetMacTexts{address, on}` lets one Mac read the texts (off for a new Mac);
`MacStatus.texts` tells the Mac. On each new text the host notifies the subscribed Macs, and each reads `MacTexts`.
**Reply.** A Mac answers a text it was shown by writing `MacReply` to the reply characteristic. The host answers only a
text still in `MacTexts` (so a Mac can't text any number), on the line it came in on, and the clients see the reply as
`HostSmsSent{origin MAC}`. A refused write gets `GATT_FAILURE`; a host without the characteristic has no replies. The
write is acknowledged when the host starts sending: its outcome reaches the clients, not the Mac.
**Control.** `SetMacControl{address, on}` lets one Mac change host settings (off for a new Mac); `MacStatus.control`
tells the Mac, and only such a Mac gets the lines, the hotspot's devices and the blocked list in `MacStatus`. It
writes `MacControl` to the control characteristic: the data line (`data_slot`), a host action, or a device block, as
the client's own commands. A write without the permission gets `GATT_INSUFFICIENT_AUTHORIZATION`, a host action
during a call the application error `0x80`, any other refusal `GATT_FAILURE`.

## Updates

`GET /api/v1/releases/host?since=<versionCode>` lists the host releases newer than `since` (newest first: version,
code, notes `{en, tr}`, apk `{url, sha256, size}`); `/releases/ios` the same for the iPhone app (no file; `store_url`
once it is in the App Store) and `/releases/android` for the Android app (with its APK, which the app installs itself;
the Android release channel serves the newest). The host checks daily and on `UpdateAction{CHECK}` and reports `HostStatus.update`. Automatic updates are a
setting on the host, `Update.auto_update`, off by default; a client switches it with `AUTO_ON` and `AUTO_OFF`.
While it is off, a newer version is reported as `AVAILABLE` and nothing is downloaded; `INSTALL_NOW` then downloads
the APK, checks its sha256 and that it is signed with the installed app's key, and installs it. While it is on, the
host downloads and checks the newest APK by itself (`READY`) and installs it on `INSTALL_NOW`, or between 02:00 and
05:00 host time once a client has been shown the update and no call is up, unless `LATER` set `deferred_until_ms`
(one day). An install never starts during a call. Every release carries notes in English and Turkish.
