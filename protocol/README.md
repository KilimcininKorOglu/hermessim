# HermesSIM protocols

| Link | Specification | Schema | Licence |
| --- | --- | --- | --- |
| Host and phone (Bluetooth LE, and the internet path below) | [`v2/SPEC.md`](v2/SPEC.md), protocol 2 | `v2/wire.proto` | see `v2/LICENSE` |
| Host and Mac (Bluetooth LE) | "Mac" below | `hermessim.proto` (Mac messages only) | MIT, `LICENSE` |

The phone clients speak only protocol 2. The Mac keeps its own GATT service and the messages in `hermessim.proto` until
it moves to protocol 2. Test vectors for protocol 2 are in `v2/vectors/`.

## Call audio over Bluetooth

Call audio (L2CAP CoC on the PSM the host names in `Welcome.audio_psm`) is a byte stream of
`[u16 LE length][u16 seq LE][u8 flags][opus]` packets, 10 ms each. A sender puts every packet waiting into one write
(one L2CAP SDU on Android): a link that carries one SDU per connection event can't take 100 single-packet writes a
second. Receivers parse the stream, never one packet per read.

## Internet mode

Used when Bluetooth is out of range and the user switched internet mode on. Bluetooth always wins when both are up.

**Key.** The phone makes 32 random bytes `K` and sends `SetRemote{on, room_key}` **over Bluetooth only** (the host
refuses it on the internet path). Derived keys, all HKDF-SHA256 with salt `"hermes-remote-v1"`, 32 bytes:

| info | use |
| --- | --- |
| `room-token` | `room_id = hex(sha256(room_token))`; the server checks this and nothing else |
| `control` | control messages |
| `audio` | call audio |

**Control.** Both phones join the Reverb (Pusher protocol 7) presence channel `presence-room.<room_id>`
(`GET /api/v1/remote` gives the WebSocket URL; `POST /api/v1/remote/auth {socket_id, channel_name, room_token
(hex), role: host|ios}` signs the subscription; member ids are `host` / `ios`; `ios` is the phone, iPhone or Android).
A message travels as client event `client-m` with data `{"b": base64(nonce12 || ChaCha20-Poly1305(control key, nonce,
plaintext))}`, plaintext = `[u64 BE unix ms][frames]`, AAD = `"h2c"` (host to phone) or `"c2h"`. `frames` are protocol
2 frames, at most 16 000 bytes per message, the same stream as over Bluetooth: Hello, resume, requests and replies
follow `v2/SPEC.md`. Receivers drop messages older than 120 s or whose nonce they have already seen. `State.remote`
says whether the host reaches the relay.

**Audio.** Each side takes a TURN allocation on the HermesSIM relay (`POST /api/v1/turn {install_id}` gives short-lived
REST credentials). The phone sends its relay address in `OpenAudioPath{address, port}`; the host answers with its own
in the reply (`AudioPath`), and each side ChannelBinds (channel 0x4000) the other's relay address. One UDP datagram
carries one audio packet: `nonce12 || ChaCha20-Poly1305(audio key, nonce, [u16 seq LE][u8 flags][opus])`, AAD `"h2c"` /
`"c2h"`, the same inner packet as the L2CAP channel (10 ms frames; the receiver's jitter buffer reorders). Allocations
are refreshed and channels re-bound every 4 minutes while a call lasts. `CloseAudioPath` from the phone, the event
`AudioPathClosed` from the host, or the call ending releases them.

## Push

When the phone can't hear the host (its system closed the app, or it is away from Bluetooth), the host has
hermessim.keremgok.tr push a sealed packet: through APNs to an iPhone, through Firebase Cloud Messaging to an Android
phone (the server picks by what the room registered; the host sends the same request either way). Push has its own
key `P`: the phone makes 32 random bytes and sends `SetPushKey{key}` over Bluetooth only whenever
`State.remote.push_key_mark` (the first 8 bytes of sha256(P)) isn't its own; internet mode can be off. Without a push
key the host uses internet mode's key `K`. Below, `room_token` and the push key are derived from P (or K) exactly as
internet mode derives them from K. The phone registers `POST /api/v1/push/register {room_token, voip_token,
alert_token | fcm_token, environment: sandbox|production, app_version}` (stored under `room_id`; one phone per room,
registering replaces it; no tokens = forget) and the host sends `POST /api/v1/push/send {room_token, kind:
call|sms|note|notice, sealed: base64, collapse_id?}`. `sealed` = `nonce12 || ChaCha20-Poly1305(push key, nonce,
[u64 BE unix ms][Packet{event}])`, AAD `"h2c"`, push key = HKDF info `push` (the phone keeps only this key where its
notification extension can read it). `SetPushKey{}` (empty), forgetting the phone or retiring the host forgets P.

| kind | when | APNs (iPhone) | FCM (Android) | phone |
| --- | --- | --- | --- | --- |
| `call` | an incoming call rings, no phone on Bluetooth, full voice | VoIP, expiration 0 | data `{k, s}`, high priority, TTL 60 s | iPhone: rings it in CallKit at once (every VoIP push must report a call); Android: rings it through Telecom; then reaches the host as usual |
| `sms` | an incoming text no phone acknowledged (`EventAck`) within 4 s | alert, mutable, collapse id `text-<seq>`, 1 day | data `{k, s}`, high priority, TTL 1 day | iPhone: the notification extension writes the text's notification; Android: shown as if the link brought it. The text arrives again with the replay; a pushed text is not acknowledged |
| `note` | a ringing call in basic mode | as `sms`, collapse id `basic-<call>` | as `sms` | "Missed call" note |
| `notice` | a `Notice` event, no phone on Bluetooth | as `sms`, collapse id `notice-<kind>-<line or x>` | as `sms` | the notice as a notification |

**Health notices.** The host sends each `Notice` once per event: battery at 15 % and at 5 % while not charging (armed
again once it charges or passes 30 %), a line that newly asks for its PIN or PUK, the monthly hotspot limit at 80 % and
100 %, a line that starts roaming (armed again once it stops; the host turns nothing off), and a charge limit the phone
keeps undoing (armed again once the limit holds). That the host has been unreachable for a while the phone works out
itself (a host that is off can't send anything).

An Android app the user force-stopped gets no push until it is opened again (Android's rule); a swiped-away one does.

## Mac

A Mac asks the host for its hotspot on a GATT service of its own, and reads the recent texts where the phone allowed
it. Its connections never count as the phone's link. Every characteristic needs an encrypted link, and the host
answers only Macs in `State.peers`; any other central gets `GATT_INSUFFICIENT_AUTHORIZATION`. The texts characteristic
and its notifications answer only a Mac whose `Peer.grants.texts` is on; another gets the same error.

| UUID | Role | Properties / security |
| --- | --- | --- |
| `0e9c0011-0971-48af-ba0b-2c5c254faec0` | Mac service | advertised in a second advertising set, apart from the phone's |
| `0e9c0012-0971-48af-ba0b-2c5c254faec0` | status: `MacStatus` | read, notify (no value when too long for the link: read again), encrypted |
| `0e9c0013-0971-48af-ba0b-2c5c254faec0` | command: `MacCommand` | write with response, encrypted |
| `0e9c0014-0971-48af-ba0b-2c5c254faec0` | secret: `MacSecret` | read, encrypted |
| `0e9c0015-0971-48af-ba0b-2c5c254faec0` | Mac pairing | advertised in place of the Mac service while the pairing window is open |
| `0e9c0016-0971-48af-ba0b-2c5c254faec0` | texts: `MacTexts` | read, notify (no value: read again), encrypted, only with the texts grant |
| `0e9c0017-0971-48af-ba0b-2c5c254faec0` | reply: `MacReply` | write with response (prepared write allowed), encrypted, only with the texts grant |
| `0e9c0018-0971-48af-ba0b-2c5c254faec0` | control: `MacControl` | write with response, encrypted, only with the control grant |

Each value is one serialised message; a long read continues at its offset. **Pairing.** The phone sends
`OpenPeerWindow{open}`; for 120 s the host advertises the pairing UUID and confirms the one Bluetooth pairing request
that arrives, then adds that Mac to `State.peers`. The Mac starts the pairing by reading an encrypted characteristic.
`SetPeer{id, remove}` takes a Mac off the list and drops its bond. A host whose Bluetooth can't run two advertising sets
reports `Peers.available = false`. **Texts.** `SetPeer{id, grants}` with `texts` lets one Mac read the texts (off for a
new Mac); `MacStatus.texts` tells the Mac. On each new text the host notifies the subscribed Macs, and each reads
`MacTexts`. **Reply.** A Mac answers a text it was shown by writing `MacReply` to the reply characteristic. The host
answers only a text still in `MacTexts` (so a Mac can't text any number), on the line it came in on, and the phones see
the reply as the event `TextByHost{origin TEXT_ORIGIN_MAC}`. A refused write gets `GATT_FAILURE`; a host without the
characteristic has no replies. The write is acknowledged when the host starts sending: its outcome reaches the phones,
not the Mac. **Control.** `SetPeer{id, grants}` with `control` lets one Mac change host settings (off for a new Mac);
`MacStatus.control` tells the Mac, and only such a Mac gets the lines, the hotspot's devices and the blocked list in
`MacStatus`. It writes `MacControl` to the control characteristic: the data line (`data_slot`), a host action, or a
device block, as the phone's own requests. A write without the grant gets `GATT_INSUFFICIENT_AUTHORIZATION`, a host
action during a call the application error `0x80`, any other refusal `GATT_FAILURE`.

## Updates

`GET /api/v1/releases/host?since=<versionCode>` lists the host releases newer than `since` (newest first: version,
code, notes `{en, tr}`, apk `{url, sha256, size}`); `/releases/ios` the same for the iPhone app (no file; `store_url`
once it is in the App Store) and `/releases/android` for the Android app (with its APK, which the app installs itself;
the Android release channel serves the newest). The host checks daily and on `UpdateControl{UPDATE_VERB_CHECK}` and
reports `State.update`. Automatic updates are a setting on the host, `Update.automatic`, off by default; a phone
switches it with `UPDATE_VERB_AUTO_ON` and `UPDATE_VERB_AUTO_OFF`. While it is off, a newer version is reported as
`UPDATE_PHASE_AVAILABLE` and nothing is downloaded; `UPDATE_VERB_INSTALL` then downloads the APK, checks its sha256 and
that it is signed with the installed app's key, and installs it. While it is on, the host downloads and checks the
newest APK by itself (`UPDATE_PHASE_READY`) and installs it on `UPDATE_VERB_INSTALL`, or between 02:00 and 05:00 host
time once a phone has been shown the update and no call is up, unless `UPDATE_VERB_LATER` set `later_until_ms` (one
day). An install never starts during a call. A failed check, download or install names its `UpdateFault`. Every
release carries notes in English and Turkish.
