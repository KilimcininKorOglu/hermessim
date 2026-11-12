#!/usr/bin/env python3
# HermesSIM wire protocol, version 2. Copyright (C) 2026 Kerem Gök.
# SPDX-License-Identifier: GPL-3.0-or-later OR LicenseRef-HermesSIM-Proprietary
"""Writes the test vectors of SPEC.md into ../vectors. Needs protoc on PATH.

Usage: python3 tools/vectors.py          (from protocol/v2)
"""
from __future__ import annotations

import hashlib
import hmac
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "vectors"

DATA, CREDIT, RESET = 0, 1, 2
URGENT, NORMAL, BULK = 0, 1, 2
MAX_BODY = 1024


def leb128(value: int) -> bytes:
    """Unsigned LEB128."""
    out = bytearray()
    while True:
        low = value & 0x7F
        value >>= 7
        out.append(low | (0x80 if value else 0))
        if not value:
            return bytes(out)


def frame(kind: int, body: bytes, more: bool = False, stream: int = 0) -> bytes:
    """One frame: header, length, body."""
    header = (kind << 6) | (int(more) << 5) | (stream << 3)
    return bytes([header]) + leb128(len(body)) + body


def cut(packet: bytes, stream: int, max_body: int = MAX_BODY) -> list[bytes]:
    """The DATA frames that carry one message."""
    pieces = [packet[i:i + max_body] for i in range(0, len(packet), max_body)] or [b""]
    return [frame(DATA, piece, more=i < len(pieces) - 1, stream=stream) for i, piece in enumerate(pieces)]


def encode(text: str) -> bytes:
    """A Packet from its protobuf text form."""
    done = subprocess.run(
        ["protoc", f"--proto_path={ROOT}", "--encode=hermessim.v2.Packet", "wire.proto"],
        input=text.encode(), capture_output=True, check=True,
    )
    return done.stdout


def x25519(scalar: bytes, point: bytes) -> bytes:
    """X25519 of RFC 7748, for the pairing vector only."""
    prime = 2 ** 255 - 19
    k = int.from_bytes(scalar, "little") & ~7 & ~(128 << 248) | (64 << 248)
    u = int.from_bytes(point, "little") & ((1 << 255) - 1)
    x1, x2, z2, x3, z3, swap = u, 1, 0, u, 1, 0
    for t in reversed(range(255)):
        bit = (k >> t) & 1
        if swap ^ bit:
            x2, x3, z2, z3 = x3, x2, z3, z2
        swap = bit
        a, b, c, d = (x2 + z2) % prime, (x2 - z2) % prime, (x3 + z3) % prime, (x3 - z3) % prime
        aa, bb, da, cb = a * a % prime, b * b % prime, d * a % prime, c * b % prime
        e = (aa - bb) % prime
        x3, z3 = (da + cb) ** 2 % prime, x1 * (da - cb) ** 2 % prime
        x2, z2 = aa * bb % prime, e * (aa + 121665 * e) % prime
    if swap:
        x2, z2 = x3, z3
    return (x2 * pow(z2, prime - 2, prime) % prime).to_bytes(32, "little")


def hkdf(ikm: bytes, salt: bytes, info: bytes, length: int = 32) -> bytes:
    """HKDF-SHA-256 for up to 32 bytes."""
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    return hmac.new(prk, info + b"\x01", hashlib.sha256).digest()[:length]


def frame_cases() -> list[dict]:
    rows = [
        ("one small DATA frame on the normal stream", [(DATA, False, NORMAL, bytes.fromhex("320708ac0212020839"))]),
        ("a DATA frame that goes on, urgent stream", [(DATA, True, URGENT, b"\x01\x02\x03")]),
        ("a 300 byte body takes a two byte length", [(DATA, False, BULK, bytes(range(256)) + bytes(44))]),
        ("an empty DATA frame", [(DATA, False, NORMAL, b"")]),
        ("CREDIT: 5 messages received, limit 20000", [(CREDIT, False, 0, leb128(5) + leb128(20000))]),
        ("RESET with reason 2", [(RESET, False, 0, leb128(2))]),
        ("three frames in one write", [(DATA, False, URGENT, b"\xaa"), (CREDIT, False, 0, leb128(1) + leb128(8192)), (DATA, False, NORMAL, b"\xbb\xcc")]),
    ]
    return [{
        "name": name,
        "frames": [{"type": k, "more": m, "stream": s, "body": b.hex()} for k, m, s, b in frames],
        "bytes": b"".join(frame(k, b, m, s) for k, m, s, b in frames).hex(),
    } for name, frames in rows]


def message_cases() -> list[dict]:
    pattern = bytes((i * 7 + 3) % 251 for i in range(2500))
    rows = [
        ("a battery update fits one frame", NORMAL, bytes.fromhex("320708ac0212020839"), MAX_BODY),
        ("exactly one full frame", NORMAL, pattern[:1024], MAX_BODY),
        ("one byte above a full frame", BULK, pattern[:1025], MAX_BODY),
        ("2500 bytes take three frames", BULK, pattern, MAX_BODY),
        ("a small limit cuts a short message", URGENT, pattern[:10], 4),
    ]
    return [{"name": name, "stream": stream, "packet": packet.hex(), "max_body": limit,
             "bytes": b"".join(cut(packet, stream, limit)).hex()} for name, stream, packet, limit in rows]


def interleave_cases() -> list[dict]:
    big = bytes((i * 5 + 1) % 253 for i in range(2100))
    urgent = bytes.fromhex("3a0408011200")
    normal = bytes.fromhex("320708ac0212020839")
    bulk = cut(big, BULK)
    wire = bulk[0] + cut(urgent, URGENT)[0] + bulk[1] + cut(normal, NORMAL)[0] + bulk[2]
    return [{
        "name": "an urgent and a normal message pass a bulk message in progress",
        "bytes": wire.hex(),
        "messages": [{"stream": URGENT, "packet": urgent.hex()}, {"stream": NORMAL, "packet": normal.hex()},
                     {"stream": BULK, "packet": big.hex()}],
    }]


PACKETS = [
    ("battery changed", "state { seq: 300 power { battery: 57 } }"),
    ("battery at 0 is sent, not left out", "state { seq: 1 power { battery: 0 charging: false } }"),
    ("hello of a phone", 'hello { min_version: 2 max_version: 2 features: 254 role: ROLE_PHONE install_id: "0123456789abcdef" '
                         'app_version: "1.0.0" device_model: "Pixel 8" state_mark { epoch: 7 seq: 41 } text_cursor: 12 call_cursor: 3 }'),
    ("hello that resumes", 'hello { min_version: 2 max_version: 2 role: ROLE_PHONE install_id: "0123456789abcdef" resume { session_id: 9 received: 17 } }'),
    ("welcome", 'welcome { version: 2 features: 126 session_id: 9 epoch: 7 audio_psm: 193 host { app_version: "1.0.0" device_model: "Redmi Note 12" '
                'firmware: "Redmi/begonia/begonia:11/RP1A" max_blocked_numbers: 50 max_mac_peers: 4 max_blocked_clients: 20 } }'),
    ("what the hotspot can do", "state { seq: 2 hotspot_limits { bands: HOTSPOT_BAND_2G bands: HOTSPOT_BAND_5G bands: HOTSPOT_BAND_AUTO "
                                "securities: HOTSPOT_SECURITY_WPA2 hidden_name: true idle_off: true call_pause: true month_limit: true } }"),
    ("a minute of hotspot use", "state { seq: 302 hotspot_usage { session_down: 5242880 session_up: 131072 today: 73400320 month: 2147483648 } }"),
    ("refused: not paired", "refuse { code: CODE_NOT_PAIRED }"),
    ("refused: wrong pairing proof", "refuse { code: CODE_PAIRING_WRONG tries_left: 2 }"),
    ("dial on the default line", 'request { id: 4 age_ms: 120 dial { number: "+905551112233" client_ref: "ref-ref-ref-ref-" } }'),
    ("dial on line 0", 'request { id: 5 dial { number: "112" line: 0 } }'),
    ("the host started to dial", "reply { id: 4 code: CODE_OK }"),
    ("a call test dial", 'request { id: 10 dial { number: "+905551112233" client_ref: "ref-ref-ref-ref-" test: true } }'),
    ("the call test did not pass: echo", "reply { id: 11 code: CODE_CALL_TEST_FAILED args: 6 }"),
    ("a text was queued", "reply { id: 12 code: CODE_OK text { text: 15 } }"),
    ("the lock after a wrong PIN", "reply { id: 6 code: CODE_WRONG_PIN args: 2 line_lock { lock: LINE_LOCK_PIN tries_left: 2 } }"),
    ("networks around the host", 'reply { id: 13 code: CODE_OK wifi_list { networks { name: "Ev" secure: true level: 4 saved: true } networks { name: "Misafir" level: 2 } } }'),
    ("forget this host", "request { id: 14 forget_host { } }"),
    ("the host retired", "bye { reason: BYE_REASON_RETIRED }"),
    ("the far end rings", 'event { seq: 5 call { call: 90 phase: CALL_PHASE_DIALING number: "05551112233" number_e164: "+905551112233" line: 1 '
                          'phase_since_ms: 1794466002000 client_ref: "ref-ref-ref-ref-" far_end_rings: true far_end_known: true } }'),
    ("the busy reply went out", 'event { seq: 16 text_by_host { to: "+905551112233" body: "Mesgulum" line: 0 at_ms: 1794466003000 origin: TEXT_ORIGIN_BUSY_REPLY } }'),
    ("the internet audio path closed", "event { audio_path_closed { } }"),
    ("wrong PIN, two tries left", "reply { id: 6 code: CODE_WRONG_PIN args: 2 }"),
    ("a negative argument", "reply { id: 7 code: CODE_FAILED args: -3 }"),
    ("turn roaming off on line 1", "request { id: 8 set_line { line: 1 roaming: false } }"),
    ("keypad tones", 'request { id: 9 send_tones { call: 88 digits: "1234#" tone_ms: 120 gap_ms: 80 } }'),
    ("a text came in", 'event { seq: 13 text_in { from: "+905551112233" body: "Kodunuz 482913 ş€" line: 1 at_ms: 1794466000000 } }'),
    ("a text was delivered", 'event { seq: 14 text_status { text: 14 client_ref: "ref-ref-ref-ref-" phase: TEXT_PHASE_DELIVERED } }'),
    ("an incoming call rings", 'event { seq: 4 call { call: 89 phase: CALL_PHASE_RINGING incoming: true number: "+905551112233" line: 0 phase_since_ms: 1794466001000 } }'),
    ("texts stored", "event_ack { texts_up_to: 14 }"),
    ("replay end with one live call", "replay_end { text_cursor: 14 call_cursor: 4 live_calls: 89 }"),
    ("signal bars of two lines", "state { seq: 301 signal { bars: 3 bars: 0 } }"),
]


def packet_cases() -> list[dict]:
    return [{"name": name, "text": text, "bytes": encode(text).hex()} for name, text in PACKETS]


def pairing_cases() -> list[dict]:
    phone_secret = bytes(range(1, 33))
    host_secret = bytes(range(101, 133))
    base = (9).to_bytes(32, "little")
    phone_key, host_key = x25519(phone_secret, base), x25519(host_secret, base)
    shared = x25519(phone_secret, host_key)
    assert shared == x25519(host_secret, phone_key)
    salt, code, install_id = bytes(range(200, 216)), "482913", b"0123456789abcdef"
    key = hkdf(shared, salt, b"hermessim pair v2")
    proof = hmac.new(key, phone_key + host_key + code.encode() + install_id, hashlib.sha256).digest()
    return [{
        "name": "a right proof",
        "phone_secret": phone_secret.hex(), "phone_key": phone_key.hex(),
        "host_secret": host_secret.hex(), "host_key": host_key.hex(),
        "shared": shared.hex(), "salt": salt.hex(), "code": code, "install_id": install_id.hex(),
        "key": key.hex(), "proof": proof.hex(),
    }]


# ---------------------------------------------------------------- internet path (protocol/README.md)

SALT = b"hermes-remote-v1"
JOIN, JOINED, PEER, LEAVE, RELAY_DATA, PUSH, PUSH_RESULT, KICKED, ERROR = range(1, 10)
HS1, HS2, HS3, SEAL = 1, 2, 3, 4
PUSH_KINDS = {"call": 1, "sms": 2, "note": 3, "notice": 4}


def derive(key: bytes, info: str) -> bytes:
    """The keys of a room or push key: HKDF-SHA-256, salt "hermes-remote-v1"."""
    return hkdf(key, SALT, info.encode())


def rotl(value: int, shift: int) -> int:
    return ((value << shift) | (value >> (32 - shift))) & 0xFFFFFFFF


def chacha_block(key: bytes, counter: int, nonce: bytes) -> bytes:
    """One ChaCha20 block of RFC 8439."""
    words = [0x61707865, 0x3320646E, 0x79622D32, 0x6B206574]
    words += [int.from_bytes(key[i:i + 4], "little") for i in range(0, 32, 4)]
    words += [counter] + [int.from_bytes(nonce[i:i + 4], "little") for i in range(0, 12, 4)]
    state = list(words)

    def quarter(a: int, b: int, c: int, d: int) -> None:
        state[a] = (state[a] + state[b]) & 0xFFFFFFFF; state[d] = rotl(state[d] ^ state[a], 16)
        state[c] = (state[c] + state[d]) & 0xFFFFFFFF; state[b] = rotl(state[b] ^ state[c], 12)
        state[a] = (state[a] + state[b]) & 0xFFFFFFFF; state[d] = rotl(state[d] ^ state[a], 8)
        state[c] = (state[c] + state[d]) & 0xFFFFFFFF; state[b] = rotl(state[b] ^ state[c], 7)

    for _ in range(10):
        for a, b, c, d in ((0, 4, 8, 12), (1, 5, 9, 13), (2, 6, 10, 14), (3, 7, 11, 15),
                           (0, 5, 10, 15), (1, 6, 11, 12), (2, 7, 8, 13), (3, 4, 9, 14)):
            quarter(a, b, c, d)
    return b"".join(((s + w) & 0xFFFFFFFF).to_bytes(4, "little") for s, w in zip(state, words))


def chacha(key: bytes, counter: int, nonce: bytes, data: bytes) -> bytes:
    stream = b"".join(chacha_block(key, counter + i, nonce) for i in range((len(data) + 63) // 64))
    return bytes(a ^ b for a, b in zip(data, stream))


def poly1305(key: bytes, message: bytes) -> bytes:
    r = int.from_bytes(key[:16], "little") & 0x0FFFFFFC0FFFFFFC0FFFFFFC0FFFFFFF
    s = int.from_bytes(key[16:], "little")
    prime, total = (1 << 130) - 5, 0
    for i in range(0, len(message), 16):
        block = message[i:i + 16] + b"\x01"
        total = (total + int.from_bytes(block, "little")) * r % prime
    return ((total + s) & ((1 << 128) - 1)).to_bytes(16, "little")


def seal(key: bytes, nonce: bytes, plain: bytes, aad: bytes = b"") -> bytes:
    """ChaCha20-Poly1305 of RFC 8439: ciphertext followed by the 16-byte tag."""
    cipher = chacha(key, 1, nonce, plain)
    pad = lambda data: data + bytes(-len(data) % 16)
    mac_data = pad(aad) + pad(cipher) + len(aad).to_bytes(8, "little") + len(cipher).to_bytes(8, "little")
    return cipher + poly1305(chacha_block(key, 0, nonce)[:32], mac_data)


def relay(kind: int, slot: int, body: bytes) -> bytes:
    return bytes([kind, slot]) + body


def relay_cases() -> list[dict]:
    token = bytes(range(32))
    collapse = b"5f1c0b8e2a7d4c6b9e3f1a2b3c4d5e6f"
    rows = [
        ("a phone joins room slot 0", JOIN, 0, bytes([2]) + token),
        ("a host joins room slot 3", JOIN, 3, bytes([1]) + token),
        ("joined, the other side is there", JOINED, 0, b"\x01"),
        ("joined, alone in the room", JOINED, 1, b"\x00"),
        ("the other side came", PEER, 0, b"\x01"),
        ("the other side left", PEER, 2, b"\x00"),
        ("leave room slot 1", LEAVE, 1, b""),
        ("data for the room in slot 0", RELAY_DATA, 0, bytes.fromhex("0400000000000000") + bytes(range(20))),
        ("a text push", PUSH, 0, (7).to_bytes(2, "big") + token + bytes([PUSH_KINDS["sms"], len(collapse)]) + collapse + bytes(range(40))),
        ("a call push without a collapse id", PUSH, 0, (8).to_bytes(2, "big") + token + bytes([PUSH_KINDS["call"], 0]) + bytes(range(30))),
        ("the push went out", PUSH_RESULT, 0, (7).to_bytes(2, "big") + b"\x00"),
        ("no phone registered for the push", PUSH_RESULT, 0, (8).to_bytes(2, "big") + b"\x01"),
        ("replaced by a newer socket of the same role", KICKED, 0, (1).to_bytes(2, "big")),
        ("a frame the server does not take", ERROR, 0, (1).to_bytes(2, "big")),
    ]
    return [{"name": name, "type": kind, "slot": slot, "body": body.hex(), "bytes": relay(kind, slot, body).hex()}
            for name, kind, slot, body in rows]


def roomkey_cases() -> list[dict]:
    rows = []
    for name, key in (("a room key", bytes(range(32))), ("another room key", bytes(range(100, 132)))):
        token = derive(key, "room-token")
        rows.append({"name": name, "key": key.hex(), "room_token": token.hex(),
                     "room_id": hashlib.sha256(token).hexdigest(), "auth": derive(key, "auth").hex(),
                     "push": derive(key, "push").hex(),
                     "collapse": derive(key, "collapse").hex()})
    return rows


def tunnel_keys(auth: bytes, shared: bytes) -> tuple[bytes, bytes]:
    prk = hmac.new(auth, shared, hashlib.sha256).digest()
    p2h = hmac.new(prk, b"p2h\x01", hashlib.sha256).digest()
    h2p = hmac.new(prk, b"h2p\x01", hashlib.sha256).digest()
    return p2h, h2p


def sealed(key: bytes, counter: int, plain: bytes) -> bytes:
    nonce = bytes(4) + counter.to_bytes(8, "big")
    return bytes([SEAL]) + counter.to_bytes(8, "big") + seal(key, nonce, plain)


def tunnel_cases() -> list[dict]:
    room_key = bytes(range(32))
    auth = derive(room_key, "auth")
    base = (9).to_bytes(32, "little")
    rows = []
    for name, phone_secret, host_secret in (("a first tunnel", bytes(range(1, 33)), bytes(range(101, 133))),
                                            ("a second tunnel of the same room", bytes(range(33, 65)), bytes(range(133, 165)))):
        phone_key, host_key = x25519(phone_secret, base), x25519(host_secret, base)
        shared = x25519(phone_secret, host_key)
        assert shared == x25519(host_secret, phone_key)
        p2h, h2p = tunnel_keys(auth, shared)
        frames_p, frames_h = bytes.fromhex("0a03010203"), bytes.fromhex("0b0405060708")
        rows.append({
            "name": name, "room_key": room_key.hex(), "auth": auth.hex(),
            "phone_secret": phone_secret.hex(), "host_secret": host_secret.hex(),
            "hs1": (bytes([HS1]) + phone_key).hex(),
            "hs2": (bytes([HS2]) + host_key + hmac.new(auth, b"H" + phone_key + host_key, hashlib.sha256).digest()).hex(),
            "hs3": (bytes([HS3]) + hmac.new(auth, b"P" + phone_key + host_key, hashlib.sha256).digest()).hex(),
            "shared": shared.hex(), "p2h": p2h.hex(), "h2p": h2p.hex(),
            "phone_plain": frames_p.hex(), "host_plain": frames_h.hex(),
            "phone_seal_0": sealed(p2h, 0, frames_p).hex(), "phone_seal_1": sealed(p2h, 1, frames_p).hex(),
            "host_seal_0": sealed(h2p, 0, frames_h).hex(),
        })
    return rows


def collapse_id(key: bytes, name: str) -> str:
    return hmac.new(derive(key, "collapse"), name.encode(), hashlib.sha256).hexdigest()[:32]


def window(steps: list[int]) -> list[bool]:
    """The phone's push window: the highest counter seen and a bit for each of the 64 below it."""
    top, bits, out = -1, 0, []
    for counter in steps:
        if counter > top:
            shift = counter - top
            bits = ((bits << shift) | 1) & ((1 << 64) - 1) if top >= 0 and shift < 64 else 1
            top = counter
            out.append(True)
        elif top - counter < 64 and not bits >> (top - counter) & 1:
            bits |= 1 << (top - counter)
            out.append(True)
        else:
            out.append(False)
    return out


def push_cases() -> list[dict]:
    push_key = bytes(range(64, 96))
    plain = (5).to_bytes(8, "big") + (1794466000000).to_bytes(8, "big") + bytes.fromhex("3a0408011200")
    nonce = bytes(range(12))
    seals = [{"name": "a sealed push", "push_key": push_key.hex(), "nonce": nonce.hex(), "plain": plain.hex(),
              "counter": 5, "sent_ms": 1794466000000, "packet": "3a0408011200",
              "sealed": (nonce + seal(derive(push_key, "push"), nonce, plain, b"h2c")).hex()}]
    collapses = [{"name": f"collapse id of {name}", "push_key": push_key.hex(), "id": name,
                  "collapse_id": collapse_id(push_key, name)} for name in ("text-14", "notice-3-1", "basic-89")]
    steps = [
        ("in order", [0, 1, 2, 3]),
        ("out of order within the window", [5, 3, 4, 2]),
        ("a repeat is refused", [7, 7, 6, 6]),
        ("64 below the top is too old", [100, 37, 36]),
        ("a big jump clears the window", [3, 200, 3, 199]),
    ]
    windows = [{"name": name, "counters": counters, "accepted": window(counters)} for name, counters in steps]
    return [{"seal": seals, "collapse": collapses, "window": windows}]


# ---------------------------------------------------------------- call audio (protocol/README.md "Call audio")

MARK, AUDIO, PROBE = 0xA5, 0xA6, 0xA7
MIN_INNER = 3                       # [u16 tick][at least one Opus byte]


def inner(tick: int, opus: bytes) -> bytes:
    return (tick & 0xFFFF).to_bytes(2, "little") + opus


def stream_packet(packet: bytes) -> bytes:
    """One packet on the L2CAP stream: mark, length, inner packet."""
    return bytes([MARK, len(packet)]) + packet


def parse_stream(data: bytes) -> tuple[list[bytes], int, bytes]:
    """The receiver's rule: packets, bytes skipped to find a mark, and what waits for more bytes."""
    packets, skipped, at = [], 0, 0
    while len(data) - at >= 2:
        length = data[at + 1]
        if data[at] != MARK or length < MIN_INNER:
            at += 1
            skipped += 1
            continue
        if len(data) - at < 2 + length:
            break
        packets.append(data[at + 2:at + 2 + length])
        at += 2 + length
    return packets, skipped, data[at:]


def audio_keys(audio_id: bytes, shared: bytes) -> tuple[bytes, bytes]:
    prk = hmac.new(audio_id, shared, hashlib.sha256).digest()
    return (hmac.new(prk, b"audio p2h\x01", hashlib.sha256).digest(),
            hmac.new(prk, b"audio h2p\x01", hashlib.sha256).digest())


def datagram(kind: int, key: bytes, counter: int, plain: bytes) -> bytes:
    head = bytes([kind]) + counter.to_bytes(4, "big")
    return head + seal(key, bytes(8) + counter.to_bytes(4, "big"), plain, head)


def audio_cases() -> list[dict]:
    a, b, c = inner(0, bytes.fromhex("78a1b2c3")), inner(1, bytes.fromhex("78d4e5")), inner(65535, bytes(range(40)))
    clean = stream_packet(a) + stream_packet(b) + stream_packet(c)
    streams = []
    for name, data in (("three packets in one write", clean),
                       ("noise before a packet is skipped", bytes.fromhex("00ff13") + stream_packet(a)),
                       ("a mark with a too short length is skipped", bytes([MARK, 2]) + stream_packet(b)),
                       ("a packet cut by the write waits for the rest", stream_packet(a) + stream_packet(c)[:10])):
        packets, skipped, rest = parse_stream(data)
        streams.append({"name": name, "bytes": data.hex(), "packets": [p.hex() for p in packets],
                        "skipped": skipped, "rest": rest.hex()})
    base = (9).to_bytes(32, "little")
    phone_secret, host_secret, audio_id = bytes(range(1, 33)), bytes(range(101, 133)), bytes(range(16))
    phone_key, host_key = x25519(phone_secret, base), x25519(host_secret, base)
    shared = x25519(phone_secret, host_key)
    assert shared == x25519(host_secret, phone_key)
    p2h, h2p = audio_keys(audio_id, shared)
    probe_ask, probe_answer = bytes([1]) + (77).to_bytes(4, "big"), bytes([2]) + (77).to_bytes(4, "big")
    keys = [{"name": "the keys of a path", "phone_secret": phone_secret.hex(), "host_secret": host_secret.hex(),
             "phone_key": phone_key.hex(), "host_key": host_key.hex(), "audio_id": audio_id.hex(),
             "shared": shared.hex(), "p2h": p2h.hex(), "h2p": h2p.hex(),
             "datagrams": [
                 {"name": "the phone's first audio", "key": "p2h", "type": AUDIO, "counter": 0, "plain": a.hex(),
                  "bytes": datagram(AUDIO, p2h, 0, a).hex()},
                 {"name": "the host's audio, counter 300", "key": "h2p", "type": AUDIO, "counter": 300,
                  "plain": c.hex(), "bytes": datagram(AUDIO, h2p, 300, c).hex()},
                 {"name": "a probe from the phone", "key": "p2h", "type": PROBE, "counter": 1,
                  "plain": probe_ask.hex(), "bytes": datagram(PROBE, p2h, 1, probe_ask).hex()},
                 {"name": "the host answers the probe", "key": "h2p", "type": PROBE, "counter": 0,
                  "plain": probe_answer.hex(), "bytes": datagram(PROBE, h2p, 0, probe_answer).hex()},
             ]}]
    return [{"stream": streams, "keys": keys}]


# The playout rule: every receiver of call audio (the host's daemon and both phone apps) plays with it.
PRIME, KEEP, WINDOW, MAX_DEPTH, RESYNC, SLOTS, MAX_GAP = 2, 1, 200, 8, 50, 64, 4


def tick_diff(a: int, b: int) -> int:
    """a - b on the 16-bit tick circle, as a signed number."""
    d = (a - b) & 0xFFFF
    return d - 0x10000 if d >= 0x8000 else d


class Playout:
    """Packets in by tick, one 10 ms tick out per take."""

    def __init__(self) -> None:
        self.slots: dict[int, int] = {}
        self.started = self.playing = False
        self.next = self.end = 0
        self.carry = self.late_run = 0
        self.win_min, self.win_n, self.trim = 1000, 0, 0
        self.received = self.lost = self.late = 0

    def depth(self) -> int:
        return tick_diff(self.end, self.next) + self.carry

    def put(self, tick: int, ticks: int) -> None:
        if not self.started:
            self.started, self.next, self.end = True, tick, tick
        if tick_diff(tick, self.next) < 0:
            self.late += 1
            self.late_run += 1
            if self.late_run < RESYNC:
                return
            self.slots.clear()
            self.next = self.end = tick
            self.playing, self.carry, self.late_run = False, 0, 0
        self.late_run = 0
        if tick_diff(tick, self.next) >= SLOTS - 1:
            self.slots.clear()
            self.next = self.end = tick
            self.playing, self.carry = False, 0
        self.slots[tick] = ticks
        if tick_diff((tick + ticks) & 0xFFFF, self.end) > 0:
            self.end = (tick + ticks) & 0xFFFF
        self.received += 1
        if not self.playing and self.depth() >= PRIME:
            self.playing = True

    def advance(self, ticks: int) -> None:
        self.next = (self.next + ticks) & 0xFFFF

    def drop(self) -> None:
        if self.carry:
            self.carry -= 1
        elif self.next in self.slots:
            self.advance(self.slots.pop(self.next))
        else:
            self.advance(1)

    def recover(self) -> str:
        for gap in range(1, MAX_GAP + 1):
            tick = (self.next + gap) & 0xFFFF
            if tick in self.slots:
                if self.slots[tick] > gap:
                    break
                self.advance(gap)
                self.carry = gap - 1
                self.lost += gap
                return f"fec {tick} {gap}"
        self.advance(1)
        self.lost += 1
        return "plc"

    def pull(self) -> str:
        if self.carry:
            self.carry -= 1
            return "carry"
        if not self.playing:
            return "silence"
        if tick_diff(self.end, self.next) <= 0:
            self.playing = False
            return "silence"
        if self.next in self.slots:
            tick, ticks = self.next, self.slots.pop(self.next)
            self.advance(ticks)
            self.carry = ticks - 1
            return f"play {tick}"
        return self.recover()

    def adapt(self) -> None:
        depth = self.depth()
        self.win_min = min(self.win_min, depth)
        self.win_n += 1
        if self.win_n >= WINDOW:
            if self.win_min > KEEP:
                self.trim = self.win_min - KEEP
            self.win_min, self.win_n = 1000, 0
        if self.trim > 0 and self.depth() > KEEP:
            self.drop()
            self.trim -= 1
        if self.depth() > MAX_DEPTH:
            self.drop()

    def take(self) -> str:
        out = self.pull()
        if out != "silence":
            self.adapt()
        return out


def playout_scenarios() -> list[tuple[str, list[tuple]]]:
    steady = [("put", t, 1) for t in range(6)] + [x for t in range(6, 260) for x in (("take",), ("put", t, 1))]
    return [
        ("10 ms packets in order", [("put", 0, 1), ("take",), ("put", 1, 1), ("take",), ("put", 2, 1),
                                    ("take",), ("take",), ("take",)]),
        ("20 ms packets play over two takes", [("put", 0, 2), ("take",), ("take",), ("put", 2, 2), ("take",),
                                               ("take",), ("take",)]),
        ("a lost 10 ms packet comes back from the next one's FEC", [("put", 0, 1), ("put", 2, 1), ("put", 3, 1),
                                                                    ("take",), ("take",), ("take",), ("take",)]),
        ("a lost 20 ms packet comes back from the next 20 ms one", [("put", 0, 2), ("put", 4, 2), ("take",),
                                                                    ("take",), ("take",), ("take",), ("take",),
                                                                    ("take",)]),
        ("a 10 ms gap before a 20 ms packet is concealed", [("put", 0, 1), ("put", 2, 2), ("take",), ("take",),
                                                            ("take",), ("take",)]),
        ("two lost ticks before a 10 ms packet: concealment, then FEC", [("put", 0, 1), ("put", 3, 1), ("take",),
                                                                         ("take",), ("take",), ("take",)]),
        ("a packet after its tick was played is late", [("put", 0, 1), ("put", 1, 1), ("put", 2, 1), ("take",),
                                                        ("take",), ("put", 0, 1), ("take",)]),
        ("running dry waits for the prime again", [("put", 0, 1), ("put", 1, 1), ("take",), ("take",), ("take",),
                                                   ("put", 2, 1), ("take",), ("put", 3, 1), ("take",), ("take",)]),
        ("10 and 20 ms packets mixed, as when the path changes", [("put", 0, 1), ("put", 1, 1), ("put", 2, 2),
                                                                  ("put", 4, 2), ("put", 6, 1), ("take",), ("take",),
                                                                  ("take",), ("take",), ("take",), ("take",),
                                                                  ("take",), ("take",)]),
        ("ticks wrap around", [("put", 65534, 1), ("put", 65535, 1), ("put", 0, 2), ("take",), ("take",),
                               ("take",), ("take",), ("take",)]),
        ("a sender that started again is followed after 50 late packets",
         [("put", 1000, 1), ("put", 1001, 1), ("take",)] + [("put", t, 1) for t in range(50)] + [("put", 50, 1),
                                                                                                 ("take",), ("take",)]),
        ("a packet far ahead moves playback to it", [("put", 0, 1), ("put", 1, 1), ("take",), ("put", 100, 1),
                                                     ("take",), ("put", 101, 1), ("take",), ("take",)]),
        ("more than 8 ticks buffered drops one per take", [("put", t, 1) for t in range(12)] + [("take",)] * 6),
        ("a buffer that stayed deep for a window is trimmed to 1 tick", steady + [("take",)] * 3),
    ]


def playout_lines() -> list[str]:
    lines = ["# The playout rule of call audio (protocol/README.md \"Playout\"), written by tools/vectors.py.",
             "# scenario <name> | put <tick> <ticks> | take <silence|play T|carry|fec T GAP|plc>",
             "# | count <received> <lost> <late>, the counters after the scenario's last step."]
    for name, steps in playout_scenarios():
        player = Playout()
        lines.append(f"scenario {name}")
        for step in steps:
            if step[0] == "put":
                player.put(step[1], step[2])
                lines.append(f"put {step[1]} {step[2]}")
            else:
                lines.append(f"take {player.take()}")
        lines.append(f"count {player.received} {player.lost} {player.late}")
    return lines


def check_aead() -> None:
    """RFC 8439 section 2.8.2."""
    plain = (b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, "
             b"sunscreen would be it.")
    out = seal(bytes(range(0x80, 0xA0)), bytes.fromhex("070000004041424344454647"), plain,
               bytes.fromhex("50515253c0c1c2c3c4c5c6c7"))
    assert out[:16].hex() == "d31a8d34648e60db7b86afbc53ef7ec2", out[:16].hex()
    assert out[-16:].hex() == "1ae10b594f09e26a7e902ecbd0600691", out[-16:].hex()


def check_x25519() -> None:
    """RFC 7748 section 5.2, first vector."""
    got = x25519(bytes.fromhex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                 bytes.fromhex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"))
    assert got.hex() == "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552", got.hex()


def main() -> None:
    check_x25519()
    check_aead()
    OUT.mkdir(exist_ok=True)
    files = {"frames": frame_cases(), "messages": message_cases(), "interleave": interleave_cases(),
             "packets": packet_cases(), "pairing": pairing_cases(), "relay": relay_cases(),
             "roomkey": roomkey_cases(), "tunnel": tunnel_cases(), "push": push_cases(), "audio": audio_cases()}
    for name, cases in files.items():
        (OUT / f"{name}.json").write_text(json.dumps(cases, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        print(f"{name}.json: {len(cases)} cases")
    lines = playout_lines()
    (OUT / "playout.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"playout.txt: {sum(line.startswith('scenario') for line in lines)} scenarios")


if __name__ == "__main__":
    main()
