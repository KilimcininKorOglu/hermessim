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
    let mut i = 0;
    while i < b.len() && matches!(b[i], b' ' | b'\t' | b'\n' | 0x0b | 0x0c | b'\r') {
        i += 1;
    }
    let mut neg = false;
    if i < b.len() && (b[i] == b'+' || b[i] == b'-') {
        neg = b[i] == b'-';
        i += 1;
    }
    let mut radix = 10u32;
    if i + 2 < b.len()
        && b[i] == b'0'
        && (b[i + 1] == b'x' || b[i + 1] == b'X')
        && b[i + 2].is_ascii_hexdigit()
    {
        radix = 16;
        i += 2;
    } else if i < b.len() && b[i] == b'0' {
        radix = 8;
    }
    let digits = i;
    let mut value: i128 = 0;
    while i < b.len() {
        let d = match b[i] {
            b'0'..=b'9' => (b[i] - b'0') as i128,
            b'a'..=b'f' => (b[i] - b'a' + 10) as i128,
            b'A'..=b'F' => (b[i] - b'A' + 10) as i128,
            _ => break,
        };
        if d >= radix as i128 {
            break;
        }
        value = value.saturating_mul(radix as i128).saturating_add(d);
        i += 1;
    }
    if i == digits {
        return (0, 0);
    }
    if neg {
        value = -value;
    }
    let clamped = value.clamp(i64::MIN as i128, i64::MAX as i128) as i64;
    (clamped, i)
}

/// strtoll(s, NULL, 0): no digits gives 0, trailing text is ignored.
pub fn c_strtoll_prefix(s: &str) -> i64 {
    c_strtoll(s).0
}

/// atoi: base 10 only, prefix parse, trailing text ignored.
pub fn c_atoi(s: &str) -> i32 {
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
    fn tune_values_are_bounded() {
        assert_eq!(tune_values(2, 1, 480), Some((2, 1, 480)));
        assert_eq!(tune_values(0, 1, 480), None);
        assert_eq!(tune_values(9, 1, 480), None);
        assert_eq!(tune_values(2, 7, 480), None);
        assert_eq!(tune_values(2, 1, 500), None);
    }
}
