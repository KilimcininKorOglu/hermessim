# HermesSIM wire protocol, version 2

Copyright (C) 2026 Kerem Gök. Dual-licensed, see `LICENSE`.

This document and `wire.proto` define how a HermesSIM host talks to a phone or a Mac. `vectors/` holds inputs and the bytes they must give; every implementation reads those files in its tests.

Status: stage 1 (the control channel). The internet carrier, call audio and Mac sessions keep their current form until stages 2, 3 and 4 replace them. Messages and fields are added to `wire.proto` until stage 1 ships; after that a field number is never reused.

## Terms

- **Host:** the Android phone that holds the SIM.
- **Peer:** a phone or a Mac that talks to the host.
- **Link:** one connection that carries frames in order and without loss: a Bluetooth LE connection, or an internet room.
- **Session:** the state both sides keep across links: the message counters and the request ids.
- **Message:** one `Packet` of `wire.proto`.

## Bluetooth LE service

The host is the GATT server.

| UUID | Name | Properties |
|---|---|---|
| `7d5f0001-3c1a-4b6e-9e42-5a1c0de51a00` | service | |
| `7d5f0002-3c1a-4b6e-9e42-5a1c0de51a00` | `down`, host to peer | notify, encrypted |
| `7d5f0003-3c1a-4b6e-9e42-5a1c0de51a00` | `up`, peer to host | write without response, encrypted |
| `7d5f00f0-3c1a-4b6e-9e42-5a1c0de51a00` | pairing | advertised only while the host shows a pairing code |

The peer asks for the largest MTU. A sender puts as many whole frames into one notification or write as fit, and splits a frame across several when it does not fit. A receiver reads the values of one direction as one byte stream.

## Frames

```
frame  = header length body
header = 1 byte: TT M SS 000
length = unsigned LEB128, at most 1024
```

| Bits | Field | Values |
|---|---|---|
| `TT` (7-6) | type | 0 DATA, 1 CREDIT, 2 RESET; 3 is not used |
| `M` (5) | more | DATA only: 1 when the message goes on in the next DATA frame of the same stream |
| `SS` (4-3) | stream | DATA only: 0 urgent, 1 normal, 2 bulk; 3 is not used |
| 2-0 | reserved | 0; a receiver that sees another value sends RESET |

**DATA.** The body is the next piece of a serialized `Packet`. A message of up to 1024 bytes is one frame with `M` = 0. A longer one is cut into frames of 1024 body bytes, all but the last with `M` = 1. A message is at most 262 144 bytes. Frames of different streams may alternate; frames of one stream never do, so each stream has at most one message in progress.

**Which stream.** Urgent: `Hello`, `Welcome`, `Refuse`, the pairing messages, `Request` and `Reply` for calls, and every `Event` that carries a `CallState`. Bulk: a `Reply` that carries a `LogPart`, and any message above 4096 bytes. Normal: all other messages. A sender with frames waiting on several streams sends urgent first, then normal, then bulk, and never holds an urgent frame behind more than one frame of another stream.

**Message numbers.** Each direction counts its messages from 1 in the order in which their last frames are sent. The receiver counts the same way. In a new session the `Hello` that opened it is the peer's message 1 and its `Welcome` is the host's message 1; in a resumed session both take the next numbers.

**CREDIT.** The body is two unsigned LEB128 numbers:

1. `received`: the number of the last message that arrived whole.
2. `limit`: the total of DATA body bytes the sender of this frame accepts on this link. The other side must not send a DATA frame that takes its total above `limit`. Both byte totals start again at 0 on every new link, also when the session is resumed, because the bytes of a lost link may have been counted by one side only.

Before the first CREDIT of a link the limit is 4096. A receiver sends CREDIT when half of its last grant is used, when a message arrived and it sent none for 200 ms, and at least once in 60 s while the link is idle. A side that hears no frame for 150 s treats the link as dead. There is no other keep-alive.

**RESET.** The body is one unsigned LEB128 reason (1 bad header, 2 bad length, 3 bad packet, 4 limit passed, 5 no session). Both sides drop the messages in progress. The peer then sends `Hello` with `resume`. A host sends reason 5 on a link that is up while it holds no session for it, for example after the host app started again.

## Session

1. The peer sends `Hello` with the range of versions it speaks, its feature bits, its role and its `install_id`.
2. The host answers `Welcome` with the version to use (the highest in both ranges) and its own feature bits, or `Refuse` with a `Code`. After `CODE_NOT_PAIRED` the link stays up for pairing.
3. The host sends what the peer lacks (see "State" and "Events") and ends with `ReplayEnd`.

A feature is in use only when both sides set its bit. A side never sends a request or a field of a feature that is not in use.

### Resume

When a link comes back, the peer puts the `session_id` and the number of the host's last whole message into `Hello.resume`. If the host still holds the session it answers `resumed` = true with its own `received`, and both sides go on counting. Each side then sends again the requests and replies above the other side's `received`:

- The peer keeps a request until its message number is acknowledged by a CREDIT or a `Welcome`.
- The host keeps the last 64 replies by request id. A request whose id it has answered gets the same reply again and is not run again.

If the host does not hold the session it answers `resumed` = false with a new `session_id`. Counters and request ids start again; the peer reports each request that was not acknowledged as not delivered.

### Requests

Every `Request` gets exactly one `Reply` with the same id, except `GetLog`, whose replies end with `LogPart.last`. `Reply.code` is `CODE_OK` or the reason; a peer shows its own text for each code and never shows text from the host. A request changes one thing, so the reply says what happened to it.

`Request.age_ms` is the time since the user asked. The host answers `CODE_TOO_OLD` when it is above 10 000 for `Dial`, `CallControl` and `SendTones`, and above 30 000 for any other request that changes something. `SendText` has no limit; its `client_ref` keeps a resent text from being sent twice.

Requests that carry or return a secret or a network name (`SetHotspotSetup.passphrase`, `GetHotspotSecret`, `ScanWifi`, `SaveWifi`, `ForgetWifi`, `SetRemote` with `on`, `SetPushKey` with a key) are answered `CODE_BLUETOOTH_ONLY` over the internet. `SetMobileData` with `on` = false is answered `CODE_CARRIES_LINK` over the internet.

`Dial` is answered as soon as the host started to dial; the call itself comes as `CallState` events that repeat `Dial.client_ref`. Until the call test has passed (`Voice.relays_audio`), `Dial` without `test` and `CallControl` with `CALL_VERB_ANSWER` are answered `CODE_CALL_TEST_NEEDED`.

### The call test

The phone dials with `Dial.test`, asks `CallTestCheck` during the call and gets a `CallTestReading`, then sends the user's answers in `CallTestVerdict`. The host answers `CODE_OK` when its last reading and the answers agree that audio went both ways and the host stayed silent; from then on `Voice.relays_audio` is true for this firmware and profile. Otherwise it answers `CODE_CALL_TEST_FAILED` with a `CallTestFault`.

### The end of a pairing

- The phone forgets the host: `ForgetHost`. The host answers `CODE_OK`, then forgets the phone's `install_id`, the keys of internet mode and push, and the Bluetooth bond.
- The host stops being a host: it sends `Bye{BYE_REASON_RETIRED}` and does the same. The peer forgets the host.

## State

The host's state is cut into the groups of `State` (fields 2 and up). After each change the host sends one `State` with only the groups that changed and the next `seq`. A group that is present replaces the peer's copy; in `Power` an absent field stays as it is.

`Welcome.epoch` changes each time the host app starts. In `Hello.state_mark` the peer names the last `State` it applied. The host then sends:

- every group, when the mark is absent or its epoch is not the current one;
- the groups that changed after `mark.seq`, in one `State`, otherwise.

This first `State` after a `Welcome` carries the host's current `seq`, not a new one, and is the peer's new base. The same holds for the answer to `GetState`: the host sends the named groups whole in a `State` with its current `seq`, before the `Reply`.

After its base, a peer that receives a `State` whose `seq` is not one above the last asks for every group with `GetState`.

A group is sent whole. `Power` is the exception: the host sends only its fields that changed, and all of its fields in a `State` that is a base.

`Signal` changes often. The host sends it at once when a line loses its signal or gets it back, and otherwise at most once in 30 s and only while the peer said it is in the foreground (the last `GetState` that named the group was at most 5 minutes ago).

`HotspotUsage` and the byte counts in `HotspotClients` change about once a minute while the hotspot is in use; they have groups of their own so that `Hotspot` is not sent again with them. The hotspot's passphrase is in no group: the phone asks for it with `GetHotspotSecret`.

## Events

Texts and calls have sequence numbers that the host keeps on disk; each kind counts apart, from 1.

- **Texts.** `Event{seq, text_in}` is a text that came in. `Event{seq, text_by_host}` is a text the host sent by itself. A text the peer sends gets its seq in `TextQueued`. `Event{text_status}` carries the seq of the text it is about in `TextStatus.text` and has no seq of its own. The peer answers with `EventAck{texts_up_to}` once it stored the texts; the host then forgets them up to that seq. In `Hello.text_cursor` the peer names the highest seq it stored, and the host sends only the texts above it.
- **Calls.** `Event{seq, call}` replaces the peer's copy of that call. After `Welcome` the host sends the `CallState` of every live call.
- **Replay end.** `ReplayEnd` closes the replay. A call the peer holds that is not in `live_calls` has ended. A peer whose stored text seq is below `text_cursor` after the replay sends `Hello` again.

`CallState.phase_since_ms` is the moment the call entered the phase and does not change in a replay.

## Pairing

The host shows a 6-digit code on its own screen while it accepts a new phone. A phone that is not paired gets `Refuse{CODE_NOT_PAIRED}` and then:

1. Phone: `PairStart{public_key}`, a fresh X25519 key.
2. Host: `PairOffer{public_key, salt, tries_left}`, or `Refuse{CODE_PAIRING_CLOSED}`.
3. Both: `shared` = X25519 of the two keys. `key` = HKDF-SHA-256(ikm = `shared`, salt = `salt`, info = "hermessim pair v2", 32 bytes).
4. Phone: `PairProof{proof}`, where `proof` = HMAC-SHA-256(`key`, phone key ‖ host key ‖ code as 6 ASCII digits ‖ `install_id`).
5. Host: when the proof is right it pins `install_id`, stops showing the code and waits for `Hello`. When it is wrong it answers `Refuse{CODE_PAIRING_WRONG}` with the tries left. After three wrong proofs it makes a new code.

A host that has a pinned phone takes a `Hello` only with that `install_id`. Pairing a phone again, also from a known Bluetooth address, needs the code again.

Known limit: this stops a device that does not know the code. It does not stop an attacker who relays the Bluetooth pairing itself and can try all codes offline against one proof; that needs a PAKE (CPace or SPAKE2), which stage 1 does not have.

## Limits

| What | Value |
|---|---|
| Frame body | 1024 bytes |
| Message | 262 144 bytes |
| Limit before the first CREDIT | 4096 bytes |
| Replies the host keeps for resume | 64 |
| Blocked numbers, blocked hotspot devices, Mac peers | `HostInfo` |

## Test vectors

Each file in `vectors/` is a JSON list of cases. `tools/vectors.py` writes them from this document's rules and `protoc`; run it again after a change and commit the result.

| File | Case fields | What a test does |
|---|---|---|
| `frames.json` | `name`, `frames` (type, more, stream, body hex), `bytes` hex | encodes the frames to `bytes`; decodes `bytes` to the frames |
| `messages.json` | `name`, `stream`, `packet` hex, `max_body`, `bytes` hex | cuts `packet` into DATA frames and gets `bytes`; reads `bytes` and gets `packet` |
| `interleave.json` | `name`, `bytes` hex, `messages` (stream, packet hex) in the order they complete | reads `bytes` and gets the messages in that order |
| `packets.json` | `name`, `text` (protobuf text form), `bytes` hex | builds the packet from its fields and gets `bytes`; parses `bytes` and gets the fields |
| `pairing.json` | keys, salt, code, install id, `key` hex, `proof` hex | derives `key` and `proof` |
