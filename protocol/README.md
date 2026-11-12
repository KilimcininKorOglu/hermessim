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

**Room key.** The phone makes 32 random bytes `R` and sends `SetRemote{on, room_key}` **over Bluetooth only** (the
host refuses it on the internet path). The phone keeps the time it handed `R` over. In a Bluetooth session in which
`R` is older than 7 days, the phone makes a new `R` and hands it over the same way; when the reply is `CODE_OK`, both
sides forget the old key and move to the new room. Without that reply the phone keeps the old key and tries again in
its next session. No clock of the other side is involved. Derived keys, all HKDF-SHA256 with salt
`"hermes-remote-v1"`, 32 bytes (vectors: `v2/vectors/roomkey.json`):

| info | use |
| --- | --- |
| `room-token` | `room_id = hex(sha256(room_token))`; the server checks this and nothing else |
| `auth` | proves the room key in the tunnel handshake; never encrypts anything |
| `audio` | call audio (until audio moves to the tunnel's keys) |

**Relay.** One WebSocket per phone, and one per host, at `wss://hermessim.keremgok.tr/api/v1/relay`. Every message
is binary: `[u8 type][u8 room slot][body]`. A phone joins the room of each of its hosts on the same socket, each in a
slot it picks (0-7, at most 8 rooms per socket); the server writes the recipient's own slot into what it forwards.
Keep-alive is the WebSocket's own ping and pong. Vectors: `v2/vectors/relay.json`.

| type | direction | body |
| --- | --- | --- |
| `JOIN` 1 | to the server | `[u8 role: 1 host, 2 phone][room_token, 32 bytes]`. A second socket of the same role in the room takes its place; the first gets `KICKED` |
| `JOINED` 2 | from the server | `[u8 1 when the other side is in the room]` |
| `PEER` 3 | from the server | `[u8 0 the other side left, 1 it came]` |
| `LEAVE` 4 | to the server | empty |
| `DATA` 5 | both | opaque, at most 16 448 bytes; forwarded unread to the other role of the room |
| `PUSH` 6 | host to the server | `[u16 request][push room_token, 32 bytes][u8 kind: 1 call, 2 sms, 3 note, 4 notice][u8 collapse length][collapse id][sealed push]` |
| `PUSH_RESULT` 7 | from the server | `[u16 request][u8 0 sent, 1 no registration, 2 not configured, 3 the push service refused, 4 server error, 5 malformed push]` |
| `KICKED` 8 | from the server | `[u16 code: 1 replaced by a newer socket of the same role]` |
| `ERROR` 9 | from the server | `[u16 code: 1 bad frame, 2 too many rooms, 3 not in that room, 4 too large]` |

The server never closes a socket for sending fast: over 256 KB/s (1 MB burst) it stops reading that socket for a
while. A message is at most 64 KB. A recipient whose queue passes 1 MB is closed; the sender is not.

**Tunnel.** When both are in the room, the phone opens a tunnel in `DATA` bodies (vectors: `v2/vectors/tunnel.json`):

| message | body |
| --- | --- |
| `HS1` phone to host | `[1][phone's fresh X25519 key e_P, 32 bytes]` |
| `HS2` host to phone | `[2][host's fresh X25519 key e_H, 32][HMAC-SHA256(auth, "H" ‖ e_P ‖ e_H)]` |
| `HS3` phone to host | `[3][HMAC-SHA256(auth, "P" ‖ e_P ‖ e_H)]` |
| `SEAL` both | `[4][u64 BE counter][ChaCha20-Poly1305(direction key, nonce = 4 zero bytes ‖ counter, frames)]` |

`PRK = HMAC-SHA256(auth, X25519(e_P, e_H))`; the phone-to-host key is `HMAC-SHA256(PRK, "p2h" ‖ 0x01)`, the
host-to-phone key `HMAC-SHA256(PRK, "h2p" ‖ 0x01)`. Each side checks the other's MAC before it uses the keys. Each
direction counts from 0 and a receiver takes only the next counter: the relay keeps order, so a gap is a loss, and a
repeat is a replay; either closes the tunnel and the phone opens a new one. A tunnel is opened again on every `JOIN`
and `PEER` arrival, when a counter reaches 2³², and after 24 hours. The host counts the phone as present only after a
right `HS3`. `frames` are protocol 2 frames, the same stream as over Bluetooth: Hello, resume, requests and replies
follow `v2/SPEC.md`. No clock takes part. `State.remote` says whether the host reaches the relay.

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
key the host uses the room key `R`. Below, `room_token` and the push key are derived from P (or R) exactly as
internet mode derives them from R. The phone registers `POST /api/v1/push/register {room_token, voip_token,
alert_token | fcm_token, environment: sandbox|production, app_version}` (stored under `room_id`; one phone per room,
registering replaces it; no tokens = forget). The host sends a push as a `PUSH` frame on its relay socket while it
has one, else as `POST /api/v1/push/send {room_token, kind: call|sms|note|notice, sealed: base64, collapse_id?}`; the
server delivers both the same way. `sealed` = `nonce12 || ChaCha20-Poly1305(push key, nonce, [u64 BE counter][u64 BE
unix ms][Packet{event}])`, AAD `"h2c"`, push key = HKDF info `push` (the phone keeps only this key where its
notification extension can read it). `SetPushKey{}` (empty), forgetting the phone or retiring the host forgets P.

**Push counter.** The host keeps one counter per push key on disk and adds 1 for each push. The phone keeps the
highest counter it opened and a bit for each of the 63 below it, on disk, shared by the app and the notification
extension. It opens a push with a higher counter, or a lower one within the window whose bit is clear, and drops any
other: push services may deliver out of order, but never a second time. The time inside is for display only.

**Collapse id.** `collapse_id` = the first 32 hex digits of `HMAC-SHA256(HKDF(P, "collapse"), name)`, where `name` is
`text-<seq>`, `notice-<kind>-<line or x>` or `basic-<call>`. The server and the push service see neither the kind
nor the line. The phone names its own notification of the same event with the same id, so the two replace each
other. Vectors: `v2/vectors/push.json`.

| kind | when | APNs (iPhone) | FCM (Android) | phone |
| --- | --- | --- | --- | --- |
| `call` | an incoming call rings, no phone on Bluetooth, full voice | VoIP, expiration 0 | data `{k, s}`, high priority, TTL 60 s | iPhone: rings it in CallKit at once (every VoIP push must report a call); Android: rings it through Telecom; then reaches the host as usual |
| `sms` | an incoming text no phone acknowledged (`EventAck`) within 4 s | alert, mutable, collapse id of `text-<seq>`, 1 day | data `{k, s}`, high priority, TTL 1 day | iPhone: the notification extension writes the text's notification; Android: shown as if the link brought it. The text arrives again with the replay; a pushed text is not acknowledged |
| `note` | a ringing call in basic mode | as `sms`, collapse id of `basic-<call>` | as `sms` | "Missed call" note |
| `notice` | a `Notice` event, no phone on Bluetooth | as `sms`, collapse id of `notice-<kind>-<line or x>` | as `sms` | the notice as a notification |

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
