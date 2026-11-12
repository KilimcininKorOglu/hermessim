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


def check_x25519() -> None:
    """RFC 7748 section 5.2, first vector."""
    got = x25519(bytes.fromhex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                 bytes.fromhex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"))
    assert got.hex() == "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552", got.hex()


def main() -> None:
    check_x25519()
    OUT.mkdir(exist_ok=True)
    files = {"frames": frame_cases(), "messages": message_cases(), "interleave": interleave_cases(),
             "packets": packet_cases(), "pairing": pairing_cases()}
    for name, cases in files.items():
        (OUT / f"{name}.json").write_text(json.dumps(cases, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        print(f"{name}.json: {len(cases)} cases")


if __name__ == "__main__":
    main()
