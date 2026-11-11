// Pure protocol/format helpers shared by the Android daemon and host-side tests. GPL-3.0-or-later.
#![allow(dead_code)]

pub const NAME_LEN: usize = 44;
pub const MAX_VALUES: usize = 4;
pub const NB_CHAIN: &str = "hermessim_block";
pub const ESCAPE_WINDOW_NS: i64 = 5_000_000_000;

/// strtoll(s, &end, 0) with a prefix parse: returns the value and how many bytes were consumed from
/// the start of `s` (0 when there were no digits). Out-of-range values saturate like strtoll+ERANGE.
pub fn c_strtoll(s: &str) -> (i64, usize) {
    let b = s.as_bytes();
    let (neg, start) = sign_after_space(b);
    let (radix, digits) = base0_radix(b, start);
    let (mut value, end) = digit_run(b, digits, radix);
    if end == digits {
        return (0, 0);
    }
    if neg {
        value = -value;
    }
    let clamped = value.clamp(i64::MIN as i128, i64::MAX as i128) as i64;
    (clamped, end)
}

/// Skip C isspace() bytes and one sign: whether the sign is '-', and the index after it.
fn sign_after_space(b: &[u8]) -> (bool, usize) {
    let mut i = b
        .iter()
        .position(|c| !matches!(c, b' ' | b'\t' | b'\n' | 0x0b | 0x0c | b'\r'))
        .unwrap_or(b.len());
    let neg = b.get(i) == Some(&b'-');
    if matches!(b.get(i), Some(b'+' | b'-')) {
        i += 1;
    }
    (neg, i)
}

/// Base 0: "0x" before a hex digit is 16 (skipped), a leading '0' is 8 (kept as a digit), else 10.
fn base0_radix(b: &[u8], i: usize) -> (u32, usize) {
    let hex = b.get(i) == Some(&b'0')
        && matches!(b.get(i + 1), Some(b'x' | b'X'))
        && b.get(i + 2).is_some_and(u8::is_ascii_hexdigit);
    if hex {
        (16, i + 2)
    } else if b.get(i) == Some(&b'0') {
        (8, i)
    } else {
        (10, i)
    }
}

/// Saturating value of the digits of `radix` from `i`, and the index after the last one.
fn digit_run(b: &[u8], mut i: usize, radix: u32) -> (i128, usize) {
    let mut value: i128 = 0;
    while let Some(d) = b.get(i).and_then(|c| (*c as char).to_digit(radix)) {
        value = value
            .saturating_mul(radix as i128)
            .saturating_add(d as i128);
        i += 1;
    }
    (value, i)
}

/// strtoll(s, NULL, 0): no digits gives 0, trailing text is ignored.
pub fn c_strtoll_prefix(s: &str) -> i64 {
    c_strtoll(s).0
}

/// The rate a PCM with the [min, max] rate interval takes: `want` when inside it, else the Opus
/// rate (48000, 16000, 24000, 12000, 8000) inside it nearest to `want`, else None (the caller keeps `want` and the open fails as before).
pub fn accepted_rate(want: i32, min: u32, max: u32) -> Option<i32> {
    let inside = |r: i32| (r as u32) >= min && (r as u32) <= max;
    if inside(want) {
        return Some(want);
    }
    let mut best: Option<i32> = None;
    for r in [48000, 16000, 24000, 12000, 8000]
        .into_iter()
        .filter(|&r| inside(r))
    {
        if best.is_none_or(|b| (r - want).abs() < (b - want).abs()) {
            best = Some(r);
        }
    }
    best
}

/// `want` channels clamped into the PCM's [min, max] channel interval.
pub fn accepted_channels(want: i32, min: u32, max: u32) -> i32 {
    if (want as u32) < min {
        min as i32
    } else if (want as u32) > max {
        max as i32
    } else {
        want
    }
}

/// atoi: base 10 only, prefix parse, trailing text ignored.
pub fn c_atoi(s: &str) -> i32 {
    let b = s.as_bytes();
    let (neg, digits) = sign_after_space(b);
    let (mut value, end) = digit_run(b, digits, 10);
    if end == digits {
        return 0;
    }
    if neg {
        value = -value;
    }
    value.clamp(i32::MIN as i128, i32::MAX as i128) as i32
}

/// A complete base-0 number: no trailing text, at least one digit.
pub fn parse_c_int(s: &str) -> Option<i64> {
    let (value, used) = c_strtoll(s);
    (used != 0 && used == s.len()).then_some(value)
}

/// "name=v1[,v2...]" from `SET call`; returns the name and up to MAX_VALUES values, or None on
/// exactly the cases the C rejects (no '=', empty name, too long, non-number, more than four).
pub fn parse_call_spec(spec: &str) -> Option<(&str, Vec<i64>)> {
    let eq = spec.rfind('=')?;
    if eq == 0 {
        return None;
    }
    let name = &spec[..eq];
    if name.len() >= NAME_LEN {
        return None;
    }
    let mut values = Vec::new();
    let mut rest = &spec[eq + 1..];
    while !rest.is_empty() {
        if values.len() == MAX_VALUES {
            return None;
        }
        let comma = rest.find(',');
        let (token, next) = match comma {
            Some(c) => (&rest[..c], Some(&rest[c + 1..])),
            None => (rest, None),
        };
        let (value, used) = c_strtoll(token);
        if used == 0 || used != token.len() {
            return None;
        }
        values.push(value);
        match next {
            Some(n) => rest = n,
            None => break,
        }
    }
    Some((name, values))
}

/// "name=value" from `SET silence`; exactly one complete number, as the C requires.
pub fn parse_silence_spec(spec: &str) -> Option<(&str, i64)> {
    let eq = spec.rfind('=')?;
    if eq == 0 {
        return None;
    }
    let name = &spec[..eq];
    if name.len() >= NAME_LEN {
        return None;
    }
    let raw = &spec[eq + 1..];
    let (value, used) = c_strtoll(raw);
    if used == 0 || used != raw.len() {
        return None;
    }
    Some((name, value))
}

pub fn valid_iface(iface: &str) -> bool {
    !iface.is_empty()
        && iface.len() <= 15
        && iface
            .bytes()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'_' || c == b'-')
}

pub fn valid_pkg(pkg: &str) -> bool {
    !pkg.is_empty()
        && pkg.len() <= 100
        && pkg
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || c == b'.' || c == b'_')
}

pub fn valid_role(role: &str) -> bool {
    role == "android.app.role.DIALER" || role == "android.app.role.SMS"
}

pub fn valid_uid(s: &str) -> Option<u32> {
    if s.is_empty() || s.len() > 9 || !s.bytes().all(|c| c.is_ascii_digit()) {
        return None;
    }
    s.parse::<u32>().ok()
}

pub fn valid_session(s: &str) -> Option<i32> {
    let v = s.parse::<i32>().ok()?;
    (v == 1 || v == 2).then_some(v)
}

pub fn tune_values(prime: i32, keep: i32, period: i32) -> Option<(i32, i32, i32)> {
    ((1..=8).contains(&prime)
        && (0..=6).contains(&keep)
        && (period == 240 || period == 480 || period == 960))
        .then_some((prime, keep, period))
}

/// The charge target band: 60 -> charge below 57, hold at 60, drain above 62.
pub fn charge_band(limit: i32) -> (i32, i32) {
    if limit <= 0 || limit >= 100 {
        return (0, 0);
    }
    let resume = (limit - 3).max(30);
    let drain = (limit + 2).min(95);
    (resume, drain)
}

/// The CHARGE? state after the node name. held: charging is stopped by us right now (the input cut, or
/// charge_behaviour off "auto"); stuck: the kernel kept undoing the cut and the limit is left alone for the hour.
pub fn charge_status_line(
    limit: i32,
    resume: i32,
    drain: i32,
    cutting: bool,
    held: bool,
    stuck: bool,
) -> String {
    format!(
        "target={} resume={} drain={} cutting={} held={} stuck={}",
        limit, resume, drain, cutting as i32, held as i32, stuck as i32
    )
}

/// The exact shell command the C daemon hands to /system/bin/sh for one uid transition.
pub fn netblock_script(uid: i32, on: bool) -> String {
    let rule = if on {
        format!(
            "$b -w 5 -C {c} -m owner --uid-owner {uid} -j REJECT 2>/dev/null || $b -w 5 -A {c} -m owner --uid-owner {uid} -j REJECT",
            c = NB_CHAIN
        )
    } else {
        format!(
            "while $b -w 5 -D {c} -m owner --uid-owner {uid} -j REJECT 2>/dev/null; do :; done",
            c = NB_CHAIN
        )
    };
    format!(
        "for b in /system/bin/iptables /system/bin/ip6tables; do $b -w 5 -N {c} 2>/dev/null; $b -w 5 -C OUTPUT -j {c} 2>/dev/null || $b -w 5 -I OUTPUT 1 -j {c}; {rule}; done",
        c = NB_CHAIN
    )
}

/// One 5 s window of the call audio pump, as logged every 500 captured frames.
#[derive(Default)]
pub struct AudioWindow {
    pub sent: u32,
    pub txdrop: u32,
    pub recv: u32,
    pub fec: u32,
    pub plc: u32,
    pub late: u32,
    pub catchup: u32,
    pub caperr: u32,
    pub peak: i32,
    pub played: u32,
    pub depth_max: u32,
    pub fps: f64,
    pub jb_ms: f64,
    pub reopen: u32,
    pub prime: i32,
    pub keep: i32,
    pub period: i32,
}

/// The "audio 5s:" log line, field for field as the C daemon writes it: the host app's diagnostics trimmer
/// matches it by position.
pub fn audio_5s_line(w: &AudioWindow) -> String {
    format!(
        "audio 5s: sent={} txdrop={} recv={} fec={} plc={} late={} catchup={} caperr={} peak={} played={} depthmax={} ({:.1} fps) | held: jb={:.1}ms reopen={} (tune prime={} keep={} period={})",
        w.sent, w.txdrop, w.recv, w.fec, w.plc, w.late, w.catchup, w.caperr, w.peak, w.played, w.depth_max, w.fps,
        w.jb_ms, w.reopen, w.prime, w.keep, w.period
    )
}

/// One call's audio totals (AUDIO? and the "audio call end:" log line): the call test and the host app's
/// end-of-call check read these keys.
#[derive(Default)]
pub struct CallTotals {
    pub cap: u32,
    pub voiced: u32,
    pub sent: u32,
    pub played: u32,
    pub peak: i32,
    /// Caller audio the client's socket had no room for.
    pub txdrop: u32,
    /// Caller audio captured while no client was connected.
    pub noclient: u32,
    /// Client audio received.
    pub recv: u32,
    /// Client audio concealed (PLC).
    pub plc: u32,
    /// Playback pulls that found nothing to play while a client was connected.
    pub starved: u32,
    /// Playback pulls while a client was connected, after its first packet.
    pub live: u32,
    /// Milliseconds the capture's good reads took, read to read with no error between.
    pub capms: u32,
    /// Failed capture reads.
    pub caperr: u32,
}

pub fn call_totals_line(t: &CallTotals) -> String {
    format!(
        "cap={} voiced={} sent={} played={} peak={} txdrop={} noclient={} recv={} plc={} starved={} live={} capms={} caperr={}",
        t.cap, t.voiced, t.sent, t.played, t.peak, t.txdrop, t.noclient, t.recv, t.plc, t.starved, t.live, t.capms,
        t.caperr
    )
}

/// The capture against the clock. A voice path that hands over less than real time loses caller audio before
/// anything here can drop it, so only the time the reads took shows it: capms against cap.
#[derive(Default)]
pub struct CapClock {
    prev_ns: Option<i64>,
    ns: u64,
}

impl CapClock {
    /// A good read at `now_ns`; returns the milliseconds counted so far.
    pub fn read(&mut self, now_ns: i64) -> u32 {
        if let Some(prev) = self.prev_ns {
            self.ns += now_ns.saturating_sub(prev).max(0) as u64;
        }
        self.prev_ns = Some(now_ns);
        (self.ns / 1_000_000) as u32
    }

    /// A failed read: the stream isn't running, so the gap up to the next good read isn't a slow capture.
    pub fn error(&mut self) {
        self.prev_ns = None;
    }
}

/// Enum item names "a|b|c", cut at a whole item before `max_len`. The separator goes before every item but the
/// first index, as the C daemon writes it, so a first item the kernel would not describe still leaves its "|".
pub fn enum_join(items: &[(u32, String)], max_len: usize) -> String {
    let mut out = String::new();
    for (index, name) in items {
        let piece = if *index > 0 {
            format!("|{}", name)
        } else {
            name.clone()
        };
        if out.len() + piece.len() >= max_len {
            break;
        }
        out.push_str(&piece);
    }
    out
}

/// A whole base-10 number, strtoll-style: leading whitespace and a sign are allowed, trailing text is not.
pub fn parse_dec_full(s: &str) -> Option<i64> {
    let (value, used) = c_strtoll_base10(s);
    (used != 0 && used == s.len()).then_some(value)
}

fn c_strtoll_base10(s: &str) -> (i64, usize) {
    let b = s.as_bytes();
    let mut i = 0;
    while i < b.len() && matches!(b[i], b' ' | b'\t' | b'\n' | 0x0b | 0x0c | b'\r') {
        i += 1;
    }
    let mut neg = false;
    if i < b.len() && (b[i] == b'+' || b[i] == b'-') {
        neg = b[i] == b'-';
        i += 1;
    }
    let digits = i;
    let mut value: i128 = 0;
    while i < b.len() && b[i].is_ascii_digit() {
        value = value
            .saturating_mul(10)
            .saturating_add((b[i] - b'0') as i128);
        i += 1;
    }
    if i == digits {
        return (0, 0);
    }
    if neg {
        value = -value;
    }
    (value.clamp(i64::MIN as i128, i64::MAX as i128) as i64, i)
}

/// The values of one call-originals line, "v1,v2...": at most MAX_VALUES, a trailing comma ends the list, any
/// other non-number rejects the line (None), as does an empty list.
pub fn parse_call_orig_values(s: &str) -> Option<Vec<i64>> {
    let mut vals = Vec::new();
    let mut rest = s;
    while !rest.is_empty() && vals.len() < MAX_VALUES {
        let (piece, next) = match rest.find(',') {
            Some(c) => (&rest[..c], Some(&rest[c + 1..])),
            None => (rest, None),
        };
        vals.push(parse_dec_full(piece)?);
        match next {
            Some(n) => rest = n,
            None => break,
        }
    }
    (!vals.is_empty()).then_some(vals)
}

/// One /proc/net/arp entry as the C daemon's sscanf("%63s 0x%x 0x%x %63s %*s %31s") reads it: the type and
/// flags need their "0x", the address and hardware fields fit 63 characters. Returns (ip, hw, dev, flags).
pub fn parse_arp_line(line: &str) -> Option<(&str, &str, &str, u32)> {
    let f: Vec<&str> = line.split_whitespace().collect();
    if f.len() < 6 || f[0].len() > 63 || f[3].len() > 63 {
        return None;
    }
    let hex = |s: &str| {
        s.strip_prefix("0x")
            .and_then(|h| u32::from_str_radix(h, 16).ok())
    };
    hex(f[1])?;
    let flags = hex(f[2])?;
    Some((f[0], f[3], f[5], flags))
}

/// The C daemon's strtok over one command line: `next` is strtok(.., " "), `rest` is strtok(NULL, "").
pub struct Tokens<'a> {
    s: &'a str,
    pos: usize,
}

impl<'a> Tokens<'a> {
    pub fn new(s: &'a str) -> Self {
        Self { s, pos: 0 }
    }

    /// The next space-separated word; leading spaces are skipped.
    pub fn next(&mut self) -> Option<&'a str> {
        let b = self.s.as_bytes();
        let mut i = self.pos;
        while i < b.len() && b[i] == b' ' {
            i += 1;
        }
        if i == b.len() {
            self.pos = b.len();
            return None;
        }
        let start = i;
        while i < b.len() && b[i] != b' ' {
            i += 1;
        }
        self.pos = i;
        Some(&self.s[start..i])
    }

    /// The rest of the line from just after the last word's delimiter, spaces kept: control names contain spaces.
    pub fn rest(&mut self) -> Option<&'a str> {
        if self.pos + 1 >= self.s.len() {
            return None;
        }
        let rest = &self.s[self.pos + 1..];
        self.pos = self.s.len();
        Some(rest)
    }
}

pub const CYCLE_MIN: i32 = 3;
pub const CYCLE_MAX: i32 = 30;

/// The detached airplane-mode cycle (NETCYCLE <seconds>): on, a few seconds, off, then off once more if the
/// setting stuck, so the radio registers again even if the app or the daemon dies in the gap. `cmd connectivity
/// airplane-mode` changes it like the quick settings tile; where it is missing, the setting plus the system's
/// broadcast. None when the seconds are outside 3-30.
pub fn net_cycle_script(seconds: i32) -> Option<String> {
    if !(CYCLE_MIN..=CYCLE_MAX).contains(&seconds) {
        return None;
    }
    Some(format!(
        "air() {{ cmd connectivity airplane-mode $1 2>/dev/null || {{ settings put global airplane_mode_on $2; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $3; }}; }}; air enable 1 true; sleep {}; air disable 0 false; sleep 2; [ \"$(settings get global airplane_mode_on)\" = 1 ] && air disable 0 false; exit 0",
        seconds
    ))
}

/// The volume escape gesture: six presses, +1 up and -1 down, inside five seconds.
#[derive(Default)]
pub struct EscapeGesture {
    seq: [i32; 6],
    at: [i64; 6],
}

impl EscapeGesture {
    pub fn push(&mut self, up: bool, now_ns: i64) -> bool {
        self.seq.copy_within(1.., 0);
        self.at.copy_within(1.., 0);
        self.seq[5] = if up { 1 } else { -1 };
        self.at[5] = now_ns;
        if self.seq == [1, 1, 1, -1, -1, -1] && self.at[5] - self.at[0] <= ESCAPE_WINDOW_NS {
            self.seq = [0; 6];
            return true;
        }
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_c_integer_bases() {
        assert_eq!(parse_c_int("0"), Some(0));
        assert_eq!(parse_c_int("42"), Some(42));
        assert_eq!(parse_c_int("-7"), Some(-7));
        assert_eq!(parse_c_int("0x10"), Some(16));
        assert_eq!(parse_c_int("010"), Some(8));
        assert_eq!(parse_c_int(""), None);
        assert_eq!(parse_c_int("x"), None);
    }

    #[test]
    fn parses_call_and_silence_specs() {
        assert_eq!(
            parse_call_spec("Voice=1,0x2,-3"),
            Some(("Voice", vec![1, 2, -3]))
        );
        assert_eq!(parse_call_spec("Voice="), Some(("Voice", vec![])));
        assert_eq!(parse_call_spec("=1"), None);
        assert_eq!(parse_call_spec("Voice=1,2,3,4,5"), None);
        assert_eq!(parse_call_spec("Voice=On"), None);
        assert_eq!(parse_silence_spec("Speaker=1"), Some(("Speaker", 1)));
        assert_eq!(parse_silence_spec("Speaker=1x"), None);
        assert_eq!(parse_silence_spec("Speaker="), None);
    }

    #[test]
    fn validates_names_interfaces_and_roles() {
        assert!(valid_iface("wlan0"));
        assert!(valid_iface("rmnet_data0"));
        assert!(!valid_iface("wlan0;rm"));
        assert!(!valid_iface("toolonginterfacename"));
        assert!(valid_pkg("com.keremgok.hermessim.host"));
        assert!(!valid_pkg("bad pkg"));
        assert!(valid_role("android.app.role.SMS"));
        assert!(!valid_role("android.app.role.HOME"));
    }

    #[test]
    fn validates_uids_and_sessions() {
        assert_eq!(valid_uid("10123"), Some(10123));
        assert_eq!(valid_uid(""), None);
        assert_eq!(valid_uid("10x"), None);
        assert_eq!(valid_session("1"), Some(1));
        assert_eq!(valid_session("3"), None);
    }

    #[test]
    fn charge_band_matches_the_c_math() {
        assert_eq!(charge_band(0), (0, 0));
        assert_eq!(charge_band(100), (0, 0));
        assert_eq!(charge_band(60), (57, 62));
        assert_eq!(charge_band(30), (30, 32));
        assert_eq!(charge_band(95), (92, 95));
        assert_eq!(charge_band(99), (96, 95));
    }

    #[test]
    fn charge_status_reports_held_and_stuck() {
        assert_eq!(
            charge_status_line(60, 57, 62, false, true, false),
            "target=60 resume=57 drain=62 cutting=0 held=1 stuck=0"
        );
        assert_eq!(
            charge_status_line(0, 0, 0, true, true, true),
            "target=0 resume=0 drain=0 cutting=1 held=1 stuck=1"
        );
    }

    #[test]
    fn netblock_commands_are_the_same_shell() {
        let on = netblock_script(10123, true);
        assert_eq!(
            on,
            "for b in /system/bin/iptables /system/bin/ip6tables; do $b -w 5 -N hermessim_block 2>/dev/null; $b -w 5 -C OUTPUT -j hermessim_block 2>/dev/null || $b -w 5 -I OUTPUT 1 -j hermessim_block; $b -w 5 -C hermessim_block -m owner --uid-owner 10123 -j REJECT 2>/dev/null || $b -w 5 -A hermessim_block -m owner --uid-owner 10123 -j REJECT; done"
        );
        let off = netblock_script(10123, false);
        assert_eq!(
            off,
            "for b in /system/bin/iptables /system/bin/ip6tables; do $b -w 5 -N hermessim_block 2>/dev/null; $b -w 5 -C OUTPUT -j hermessim_block 2>/dev/null || $b -w 5 -I OUTPUT 1 -j hermessim_block; while $b -w 5 -D hermessim_block -m owner --uid-owner 10123 -j REJECT 2>/dev/null; do :; done; done"
        );
    }

    #[test]
    fn c_number_conversions_match() {
        assert_eq!(c_strtoll("0"), (0, 1));
        assert_eq!(c_strtoll("0x"), (0, 1));
        assert_eq!(c_strtoll("0x10"), (16, 4));
        assert_eq!(c_strtoll("010"), (8, 3));
        assert_eq!(c_strtoll("08"), (0, 1));
        assert_eq!(c_strtoll("  -7x"), (-7, 4));
        assert_eq!(c_strtoll("x"), (0, 0));
        assert_eq!(c_strtoll("+0Xff"), (255, 5));
        assert_eq!(c_strtoll("99999999999999999999"), (i64::MAX, 20));
    }

    #[test]
    fn c_prefix_and_atoi_conversions_match() {
        assert_eq!(c_strtoll_prefix("12junk"), 12);
        assert_eq!(c_atoi("0x10"), 0);
        assert_eq!(c_atoi("  -42x"), -42);
        assert_eq!(c_atoi(""), 0);
    }

    #[test]
    fn escape_needs_six_presses_and_the_order() {
        let mut g = EscapeGesture::default();
        let mut fired = false;
        for (i, up) in [true, true, true].into_iter().enumerate() {
            fired |= g.push(up, i as i64 * 100);
        }
        for (i, up) in [false, false, false].into_iter().enumerate() {
            fired |= g.push(up, 1000 + i as i64 * 100);
        }
        assert!(fired);

        let mut g = EscapeGesture::default();
        let mut fired = false;
        for (i, up) in [true, true, false, true, false, false]
            .into_iter()
            .enumerate()
        {
            fired |= g.push(up, i as i64 * 100);
        }
        assert!(!fired);

        let mut g = EscapeGesture::default();
        let mut fired = false;
        for (i, up) in [true, true, true, false, false, false]
            .into_iter()
            .enumerate()
        {
            fired |= g.push(up, i as i64 * 1_000_000_001);
        }
        assert!(!fired);
    }

    #[test]
    fn the_5s_audio_line_keeps_the_c_field_order() {
        let w = AudioWindow {
            sent: 500,
            txdrop: 0,
            recv: 499,
            peak: 20458,
            played: 500,
            depth_max: 4,
            fps: 99.94,
            jb_ms: 22.0,
            prime: 2,
            keep: 1,
            period: 480,
            ..Default::default()
        };
        assert_eq!(
            audio_5s_line(&w),
            "audio 5s: sent=500 txdrop=0 recv=499 fec=0 plc=0 late=0 catchup=0 caperr=0 peak=20458 played=500 depthmax=4 (99.9 fps) | held: jb=22.0ms reopen=0 (tune prime=2 keep=1 period=480)"
        );
        let idle = AudioWindow {
            jb_ms: -1.0,
            ..Default::default()
        };
        assert!(audio_5s_line(&idle).contains("| held: jb=-1.0ms reopen=0"));
    }

    #[test]
    fn call_totals_use_the_c_keys_in_order() {
        let t = CallTotals {
            cap: 1200,
            voiced: 800,
            sent: 1190,
            played: 1195,
            peak: 21000,
            txdrop: 3,
            noclient: 7,
            recv: 1180,
            plc: 12,
            starved: 5,
            live: 1150,
            capms: 14100,
            caperr: 2,
        };
        assert_eq!(
            call_totals_line(&t),
            "cap=1200 voiced=800 sent=1190 played=1195 peak=21000 txdrop=3 noclient=7 recv=1180 plc=12 starved=5 live=1150 capms=14100 caperr=2"
        );
    }

    #[test]
    fn the_capture_clock_skips_the_gap_after_an_error() {
        let ms = 1_000_000i64;
        let mut c = CapClock::default();
        assert_eq!(c.read(1000 * ms), 0); // the first read only starts the clock
        assert_eq!(c.read(1010 * ms), 10);
        assert_eq!(c.read(1022 * ms), 22);
        c.error();
        assert_eq!(c.read(1522 * ms), 22); // 500 ms of failing stream is not slow capture
        assert_eq!(c.read(1534 * ms), 34);
    }

    #[test]
    fn the_network_cycle_runs_three_to_thirty_seconds() {
        assert_eq!(net_cycle_script(2), None);
        assert_eq!(net_cycle_script(31), None);
        assert_eq!(net_cycle_script(0), None);
        assert_eq!(
            net_cycle_script(5).as_deref(),
            Some("air() { cmd connectivity airplane-mode $1 2>/dev/null || { settings put global airplane_mode_on $2; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $3; }; }; air enable 1 true; sleep 5; air disable 0 false; sleep 2; [ \"$(settings get global airplane_mode_on)\" = 1 ] && air disable 0 false; exit 0")
        );
        assert!(net_cycle_script(3).is_some());
        assert!(net_cycle_script(30).is_some());
    }

    #[test]
    fn enum_items_keep_the_c_separators_and_cut() {
        let items = vec![
            (0, "Off".to_string()),
            (1, "On".to_string()),
            (2, "Auto".to_string()),
        ];
        assert_eq!(enum_join(&items, 1500), "Off|On|Auto");
        // The kernel would not describe item 0: the C output still starts with the separator.
        assert_eq!(enum_join(&items[1..], 1500), "|On|Auto");
        // Cut before a whole item, never inside one.
        assert_eq!(enum_join(&items, 8), "Off|On");
    }

    #[test]
    fn call_originals_values_follow_strtoll() {
        assert_eq!(parse_call_orig_values("1,0,-3"), Some(vec![1, 0, -3]));
        assert_eq!(parse_call_orig_values("1,"), Some(vec![1]));
        assert_eq!(parse_call_orig_values("1,2,3,4,5"), Some(vec![1, 2, 3, 4]));
        assert_eq!(parse_call_orig_values(""), None);
        assert_eq!(parse_call_orig_values("1,,2"), None);
        assert_eq!(parse_call_orig_values("1x"), None);
        assert_eq!(parse_call_orig_values(" 7"), Some(vec![7]));
    }

    #[test]
    fn arp_lines_need_the_hex_prefixes() {
        let line = "192.168.43.12    0x1         0x2         aa:bb:cc:dd:ee:ff     *        ap0";
        assert_eq!(
            parse_arp_line(line),
            Some(("192.168.43.12", "aa:bb:cc:dd:ee:ff", "ap0", 2))
        );
        assert_eq!(parse_arp_line("192.168.43.12 1 2 aa:bb *  ap0"), None);
        assert_eq!(parse_arp_line("192.168.43.12 0x1 0xzz aa:bb * ap0"), None);
        assert_eq!(parse_arp_line("192.168.43.12 0x1 0x2 aa:bb *"), None);
    }

    #[test]
    fn a_control_name_is_the_rest_of_the_line() {
        let mut t = Tokens::new("HAS Voice Tx Device Mute");
        assert_eq!(t.next(), Some("HAS"));
        assert_eq!(t.rest(), Some("Voice Tx Device Mute"));
        assert_eq!(t.next(), None);

        let mut t = Tokens::new("  SET  mute.tx  Voice Tx");
        assert_eq!(t.next(), Some("SET"));
        assert_eq!(t.next(), Some("mute.tx"));
        assert_eq!(t.rest(), Some(" Voice Tx"));
    }

    #[test]
    fn a_missing_control_name_is_none() {
        let mut t = Tokens::new("HAS");
        assert_eq!(t.next(), Some("HAS"));
        assert_eq!(t.rest(), None);

        let mut t = Tokens::new("HAS ");
        assert_eq!(t.next(), Some("HAS"));
        assert_eq!(t.rest(), None);
    }

    #[test]
    fn a_pcm_rate_falls_back_to_the_nearest_opus_rate() {
        assert_eq!(accepted_rate(16000, 8000, 48000), Some(16000));
        assert_eq!(accepted_rate(44100, 8000, 16000), Some(16000));
        assert_eq!(accepted_rate(4000, 12000, 48000), Some(12000));
        assert_eq!(accepted_rate(44100, 44100, 44100), Some(44100));
        assert_eq!(accepted_rate(32000, 44100, 44100), None);
    }

    #[test]
    fn pcm_channels_are_clamped_into_the_interval() {
        assert_eq!(accepted_channels(1, 2, 8), 2);
        assert_eq!(accepted_channels(4, 1, 2), 2);
        assert_eq!(accepted_channels(2, 1, 8), 2);
    }

    #[test]
    fn tune_values_are_bounded() {
        assert_eq!(tune_values(2, 1, 480), Some((2, 1, 480)));
        assert_eq!(tune_values(0, 1, 480), None);
        assert_eq!(tune_values(9, 1, 480), None);
        assert_eq!(tune_values(2, 7, 480), None);
        assert_eq!(tune_values(2, 1, 500), None);
    }
}
