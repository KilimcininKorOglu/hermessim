# HermesSIM protocols

| Link | Specification | Schema | Licence |
| --- | --- | --- | --- |
| Host and its peers, the phone and up to 4 Macs (Bluetooth LE, and the phone's internet path below) | [`v2/SPEC.md`](v2/SPEC.md), protocol 2 | `v2/wire.proto` | see `v2/LICENSE` |

The phones and the Macs speak only protocol 2. Test vectors are in `v2/vectors/`.

## Call audio

**Packet.** One Opus packet with its time: `[u16 tick LE][opus]`. `tick` counts 10 ms steps of the sender's clock and
names the packet's first sample; it goes on through silence (a DTX gap is a gap in ticks). A packet holds 10 ms
over Bluetooth and 20 ms over the internet; the receiver reads the length from the Opus TOC and plays both, mixed in
one call when the path changes. An Opus packet is at most 250 bytes. Phones encode at most wideband (16 kHz audio,
the most the mobile network carries), 24 kbit/s, with in-band FEC.

**Bluetooth.** L2CAP CoC on the PSM the host names in `Welcome.audio_psm`. The stream is a row of
`[u8 0xA5][u8 length][packet]` (length 3 to 252). A sender puts every packet waiting into one write (one L2CAP SDU
on Android): a link that carries one SDU per connection event can't take 100 single-packet writes a second. A
receiver parses the stream, never one packet per read; on a byte that is not `0xA5`, or a length below 3, it skips one
byte, counts it and looks again, and the channel stays open. Each sender keeps at most 100 ms of audio waiting for
the link and drops the oldest packet when more comes.

**Internet.** A path carries datagrams `[u8 type][u32 counter BE][sealed]`: type `0xA6` audio (`sealed` holds one
packet), `0xA7` probe (`sealed` holds `[u8 1 ask, 2 answer][u32 probe id BE]`). `sealed` is
ChaCha20-Poly1305(direction key, nonce = 8 zero bytes ‖ counter, AAD = the 5 bytes before it). Each direction counts
from 0 across audio and probes. A receiver takes a counter once, within 64 below the highest it took (the push
window's rule); others are dropped. Datagrams never start with a STUN (0x00, 0x01) or ChannelData (0x40-0x7F) byte.
Vectors: `v2/vectors/audio.json`.

**Path keys.** `OpenAudioPath` carries the phone's fresh X25519 key and 16 random bytes `audio_id`; `AudioPath` the
host's fresh key. `PRK = HMAC-SHA256(audio_id, X25519(phone, host))`, the phone-to-host key
`HMAC-SHA256(PRK, "audio p2h" ‖ 0x01)`, the host-to-phone key `HMAC-SHA256(PRK, "audio h2p" ‖ 0x01)`. The control
channel that carried the two messages (the bonded Bluetooth link or the tunnel) vouches for them.

**Candidates.** Each side asks the TURN server's STUN for its public address over UDP (a `DIRECT` candidate). A phone
that gets no answer within 1 s has no UDP: it takes a TURN allocation over TCP 3478, then over TLS 443
(`turns:`), and offers it as a `RELAY` candidate. The host takes a UDP allocation only when the phone offered no
relay, so a path has at most one relay. Addresses are 4 or 16 bytes; a side whose peer has only IPv6 candidates asks
its allocation for an IPv6 relay (`REQUESTED-ADDRESS-FAMILY`). `POST /api/v1/turn {install_id}` gives short-lived
REST credentials and the server's `turn:`/`turns:` URLs.

**Probes.** Once the path's messages are through, both sides send a probe to every candidate of the other, every
50 ms, at most 20 times, and answer each probe they open on the address it came from. A direct answer wins over a
relay answer. The side with the allocation lets the other side's public IPv4 or IPv6 address in (CreatePermission),
learns the peer's real address from the first datagram it opens (a NAT may change the port), and then ChannelBinds
it; until then it sends with Send indications. Every TURN answer that carries MESSAGE-INTEGRITY is checked; one that
fails is dropped. Allocations are refreshed every 4 minutes and released with `Refresh(lifetime=0)`.

**Ready path.** When internet mode is on, the phone opens the path as the call's audio starts, over the control
channel it has (usually Bluetooth). An unused path sends a probe every 5 s, which keeps NAT bindings and permissions
alive. The phone sends its audio on the path it chooses: Bluetooth while it works, else the internet path. The host
sends on the path the phone's latest packet came on, so a change needs no message, and neither side stops its audio
engine; the sender only changes where packets go and their length (10 or 20 ms).

**Playout.** One rule in every receiver (vectors: `v2/vectors/playout.txt`). Packets go into a ring by tick; depth is
the ticks between the next tick to play and the end of the last packet, plus the decoded ticks still waiting. Each
10 ms the player takes one tick:

- Before it plays it waits for 2 ticks; when it runs dry it waits for 2 ticks again.
- A packet at the next tick is decoded whole; a 20 ms packet's second half plays at the next take.
- A missing tick: the first packet after it within 4 ticks is looked at. When the gap to it is at least that packet's
  length, the gap is decoded from the packet's FEC (`opus_decode` with `decode_fec` and the gap as frame size: Opus
  conceals the part FEC does not cover). Otherwise one tick is concealed.
- A packet older than the next tick is late and dropped; after 50 late packets in a row the sender is taken to have
  started again and playback follows it. A packet 63 or more ticks ahead moves playback to it.
- Over 2 s windows, when the smallest depth was above 1 tick, the excess is dropped one tick per take; above 8 ticks
  one tick is dropped at every take.

**Report.** Every 5 s of a call the phone sends `AudioReport` and the host answers with its own (`v2/SPEC.md`). Each
side sets its encoder's expected loss from the other's numbers.

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

**Audio.** Call audio does not travel in the tunnel: it has a path of its own, see "Call audio".

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

A Mac is a protocol 2 peer with `ROLE_MAC` on the same Bluetooth service, frames and session as the phone (SPEC.md "Macs"). It never uses the internet path. The host keeps a second advertising set for the Macs, so a Mac finds the host while the phone is connected: it names the Mac pairing UUID `7d5f00f1-3c1a-4b6e-9e42-5a1c0de51a00` while the host takes a new Mac and the service otherwise. A host whose Bluetooth can't run two advertising sets reports `Peers.available = false`.

**Pairing.** The phone sends `OpenPeerWindow{open}`. For 120 s the host takes a new Mac: it confirms the Bluetooth pairing request, and `Peers.code` carries a fresh 6-digit code that the phone shows. The Mac says `Hello`, gets `Refuse{CODE_NOT_PAIRED}`, and pairs with that code exactly as a phone pairs with the code on the host's screen. The host then lists the Mac in `Peers` by its `install_id`, named by its `Hello.device_model`. `SetPeer{id, remove}` takes a Mac off the list, ends its link and drops its bond.

**Grants.** `SetPeer{id, grants}` sets what one Mac may do; both grants are off for a new Mac. Every Mac may switch the hotspot (`SetHotspot`, with `off_when_unused_s`), read its passphrase (`GetHotspotSecret`) and report a failed hotspot check (`HostAction{HOST_VERB_HOTSPOT_NO_INTERNET}`). With `control` it may also set the data line, block hotspot devices and run the other host actions, and its view of the state holds the lines and the hotspot's devices. With `texts` it gets each text that comes in and, after its `Welcome`, those of the last 10 minutes; it may answer one of them with `SendText`, only to its sender and on its line. The phones see such a reply as `TextByHost{origin TEXT_ORIGIN_MAC}`. Any other request of a Mac gets `CODE_NOT_ALLOWED`, and every request gets its own `Reply`.

**State.** A Mac gets each `State` with the phone's `seq`, holding only the groups it may see, and `peers` with only its own entry, so it learns its grants there. `Hotspot.devices` counts the devices on the hotspot for a Mac without `control`. A Mac on the hotspot runs the hotspot check as a phone does (SPEC.md "The hotspot check").

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
