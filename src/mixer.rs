// ALSA mixer ownership, call routing, safe mode and call originals. GPL-3.0-or-later.
#![cfg(target_os = "android")]

use std::io::Write;
use std::os::unix::fs::OpenOptionsExt;
use std::os::unix::io::RawFd;

use libc::{c_char, c_int, c_long};

use crate::alsa::*;
use crate::platform::{self, kernel_name};
use crate::protocol::{
    c_atoi, c_strtoll, c_strtoll_prefix, parse_call_orig_values, CallValuesError, MAX_VALUES,
    NAME_LEN,
};

pub const MAX_MANAGED: usize = 64;
pub const MAX_CALL_CONTROLS: usize = 64;
const OWN_WRITE_GRACE_NS: i64 = 100_000_000;
const CRUMB: &str = "/data/adb/hermessim/applying";
const CRUMB_LINGER_NS: i64 = 5_000_000_000;
const SAFE_FILE: &str = "/data/adb/hermessim/safe";
const CALL_ORIG: &str = "/data/adb/hermessim/call-originals";

#[derive(Clone)]
pub struct Profile {
    pub capture: String,
    pub rec_config: String,
    pub inject1: String,
    pub inject2: String,
    pub mute_tx: String,
    pub mute_rx: String,
    pub vsid: i64,
    pub ramp: i64,
    pub capture_pcm: i32,
    pub capture_rate: i32,
    pub capture_channels: i32,
    pub inject_pcm: i32,
    pub inject_rate: i32,
    pub inject_channels: i32,
    pub loopback: bool,
}

impl Default for Profile {
    fn default() -> Self {
        Self {
            capture: String::new(),
            rec_config: String::new(),
            inject1: String::new(),
            inject2: String::new(),
            mute_tx: "Voice Tx Device Mute".into(),
            mute_rx: "Voice Rx Device Mute".into(),
            vsid: 4294967295,
            ramp: 20,
            capture_pcm: 0,
            capture_rate: 16000,
            capture_channels: 1,
            inject_pcm: 0,
            inject_rate: 48000,
            inject_channels: 2,
            loopback: false,
        }
    }
}

#[derive(Clone)]
pub struct Managed {
    pub name: String,
    pub numid: u32,
    pub count: u32,
    pub want: [i64; MAX_VALUES],
    pub nwant: usize,
    pub orig: ElemValue,
    pub have_orig: bool,
    pub write_only: bool,
    pub enumerated: bool,
    pub last_write_ns: i64,
    pub info: ElemInfo,
    pub reapply_sec: i64,
    pub reapplies: u32,
    pub paused_until_sec: i64,
}

impl Managed {
    fn empty(name: &str, numid: u32, info: ElemInfo) -> Self {
        Self {
            name: name.to_string(),
            numid,
            count: info.count,
            want: [0; MAX_VALUES],
            nwant: 0,
            orig: unsafe { std::mem::zeroed() },
            have_orig: false,
            write_only: false,
            enumerated: info.type_ == ELEM_TYPE_ENUMERATED,
            last_write_ns: 0,
            info,
            reapply_sec: 0,
            reapplies: 0,
            paused_until_sec: 0,
        }
    }
}

#[derive(Clone)]
pub struct CallControl {
    pub name: String,
    pub values: Vec<i64>,
}

pub struct Crumb {
    pub boot_id: String,
    pub safe: bool,
    until_ns: i64,
}

impl Crumb {
    pub fn new() -> Self {
        Self {
            boot_id: String::new(),
            safe: false,
            until_ns: 0,
        }
    }

    fn boot_reason() -> String {
        unsafe {
            let mut buf = [0u8; 96];
            let mut n =
                __system_property_get(c"sys.boot.reason".as_ptr(), buf.as_mut_ptr() as *mut c_char);
            if n <= 0 {
                n = __system_property_get(
                    c"ro.boot.bootreason".as_ptr(),
                    buf.as_mut_ptr() as *mut c_char,
                );
            }
            if n > 0 {
                let n = (n as usize).min(buf.len());
                let end = buf[..n].iter().position(|&b| b == 0).unwrap_or(n);
                String::from_utf8_lossy(&buf[..end]).into_owned()
            } else {
                String::new()
            }
        }
    }

    fn boot_was_normal() -> (bool, String) {
        let raw = Self::boot_reason();
        let why = if raw.is_empty() {
            "unknown".to_string()
        } else {
            raw.clone()
        };
        if raw.is_empty() {
            return (false, why);
        }
        let low = raw.to_ascii_lowercase();
        const CRASH: &[&str] = &[
            "panic", "watchdog", "wdog", "wdt", "crash", "oops", "kernel", "hard", "hw_reset",
            "abnormal", "fault", "bug", "sdi", "ramdump", "unknown", "warm", "reset",
        ];
        if CRASH.iter().any(|w| low.contains(w)) {
            return (false, why);
        }
        let normal = low.starts_with("reboot")
            || low.starts_with("shutdown")
            || low.starts_with("cold")
            || low.contains("userrequested");
        (normal, why)
    }

    pub fn start(&mut self) {
        self.boot_id =
            std::fs::read_to_string("/proc/sys/kernel/random/boot_id").unwrap_or_default();
        let prev = std::fs::read_to_string(CRUMB).unwrap_or_default();
        if !prev.is_empty() && prev != self.boot_id {
            let (normal, why) = Self::boot_was_normal();
            if normal {
                crate::logw!(
                    "the last boot ended while audio controls were being written, but by an ordinary '{}': not safe mode",
                    why
                );
            } else {
                self.safe = true;
                crate::loge!(
                    "SAFE MODE: the last boot ended ('{}') while audio controls were being written; none are written this boot",
                    why
                );
                if let Ok(mut f) = create_0600(SAFE_FILE) {
                    let _ = f.write_all(self.boot_id.as_bytes());
                    let _ = f.sync_all();
                }
            }
        }
        let _ = std::fs::remove_file(CRUMB);
        let safe = std::fs::read_to_string(SAFE_FILE).unwrap_or_default();
        if !safe.is_empty() && safe == self.boot_id {
            if !self.safe {
                crate::loge!("SAFE MODE (this boot): no mixer writes");
            }
            self.safe = true;
        } else if !safe.is_empty() {
            let _ = std::fs::remove_file(SAFE_FILE);
        }
    }

    pub fn boot_line(&self) -> String {
        let id = self.boot_id.split('\n').next().unwrap_or("");
        format!("boot {}", id)
    }

    pub fn arm(&mut self) {
        if self.boot_id.is_empty() {
            return;
        }
        let now = platform::now_ns(libc::CLOCK_MONOTONIC);
        if self.until_ns == 0 {
            if let Ok(mut f) = create_0600(CRUMB) {
                let _ = f.write_all(self.boot_id.as_bytes());
                let _ = f.sync_all();
            }
        }
        self.until_ns = now + CRUMB_LINGER_NS;
    }

    pub fn clear(&mut self) {
        if self.until_ns != 0 {
            let _ = std::fs::remove_file(CRUMB);
        }
        self.until_ns = 0;
    }

    /// Milliseconds until the breadcrumb goes, or -1.
    pub fn tick(&mut self) -> i64 {
        if self.until_ns == 0 {
            return -1;
        }
        let now = platform::now_ns(libc::CLOCK_MONOTONIC);
        let ms = (self.until_ns - now) / 1_000_000;
        if ms <= 0 {
            self.clear();
            -1
        } else {
            ms
        }
    }
}

extern "C" {
    fn __system_property_get(name: *const c_char, value: *mut c_char) -> c_int;
}

pub struct Mixer {
    pub card: i32,
    pub ctl: RawFd,
    pub profile: Profile,
    pub managed: Vec<Managed>,
    pub perm: Vec<Managed>,
    pub call_controls: Vec<CallControl>,
    pub call: bool,
    pub session: i32,
    pub remutes: u32,
    pub safe: bool,
    pub crumb: Crumb,
}

impl Mixer {
    pub fn new(card: i32, ctl: RawFd) -> Self {
        Self {
            card,
            ctl,
            profile: Profile::default(),
            managed: Vec::new(),
            perm: Vec::new(),
            call_controls: Vec::new(),
            call: false,
            session: 0,
            remutes: 0,
            safe: false,
            crumb: Crumb::new(),
        }
    }

    pub fn audio_cfg(&self) -> crate::audio::AudioCfg {
        crate::audio::AudioCfg {
            card: self.card,
            capture_pcm: self.profile.capture_pcm,
            capture_rate: self.profile.capture_rate,
            capture_channels: self.profile.capture_channels,
            inject_pcm: self.profile.inject_pcm,
            inject_rate: self.profile.inject_rate,
            inject_channels: self.profile.inject_channels,
            loopback: self.profile.loopback,
        }
    }

    pub fn manage_managed(&mut self, name: &str, want: &[i64], n: usize, write_only: bool) -> bool {
        let ctl = self.ctl;
        let safe = self.safe;
        let crumb = &mut self.crumb;
        let (list, other) = (&mut self.managed, &self.perm);
        let w = Want {
            name,
            values: want,
            n,
            write_only,
        };
        manage_in(ctl, safe, crumb, list, other, &w)
    }

    pub fn manage_perm(&mut self, name: &str, want: &[i64], n: usize, write_only: bool) -> bool {
        let ctl = self.ctl;
        let safe = self.safe;
        let crumb = &mut self.crumb;
        let (list, other) = (&mut self.perm, &self.managed);
        let w = Want {
            name,
            values: want,
            n,
            write_only,
        };
        manage_in(ctl, safe, crumb, list, other, &w)
    }

    pub fn set_by_name(&mut self, name: &str, values: &[i64], n: usize) {
        if name.is_empty() {
            return;
        }
        let Some(info) = ctl_info(self.ctl, name) else {
            return;
        };
        let mut tmp = Managed::empty(name, info.id.numid, info);
        tmp.write_only = false;
        let ctl = self.ctl;
        let safe = self.safe;
        let crumb = &mut self.crumb;
        if ctl_write(ctl, safe, crumb, &mut tmp, values, n) {
            crate::logi!(
                "set '{}' ({} ...)",
                name,
                values.first().copied().unwrap_or(0)
            );
        }
    }

    pub fn clear_device_mutes(&mut self) {
        let off = [0i64, self.profile.vsid, self.profile.ramp];
        let tx = self.profile.mute_tx.clone();
        let rx = self.profile.mute_rx.clone();
        self.set_by_name(&tx, &off, 3);
        self.set_by_name(&rx, &off, 3);
    }

    pub fn restore_all(&mut self, why: &str) {
        crate::audio::call_end();
        for i in (0..self.managed.len()).rev() {
            let write_only = self.managed[i].write_only;
            let name = self.managed[i].name.clone();
            if write_only {
                let off = [0i64, self.profile.vsid, self.profile.ramp];
                let ctl = self.ctl;
                let safe = self.safe;
                let crumb = &mut self.crumb;
                let m = &mut self.managed[i];
                ctl_write(ctl, safe, crumb, m, &off, 3);
            } else {
                let ctl = self.ctl;
                let safe = self.safe;
                let crumb = &mut self.crumb;
                let m = &mut self.managed[i];
                ctl_restore(ctl, safe, crumb, m);
            }
            crate::logi!("restored '{}' ({})", name, why);
        }
        self.managed.clear();
        self.call = false;
        self.session = 0;
        self.clear_device_mutes();
        if self.ctl >= 0 && !self.safe {
            let _ = std::fs::remove_file(CALL_ORIG);
        }
    }

    pub fn restore_perm(&mut self) {
        for i in 0..self.perm.len() {
            let name = self.perm[i].name.clone();
            let ctl = self.ctl;
            let safe = self.safe;
            let crumb = &mut self.crumb;
            let m = &mut self.perm[i];
            ctl_restore(ctl, safe, crumb, m);
            crate::logi!("restored silence control '{}'", name);
        }
    }

    pub fn call_start(&mut self, session: i32) -> i32 {
        if self.call && session == self.session {
            crate::logi!("CALL_START session={}: already active, ignored", session);
            return self.managed.len() as i32;
        }
        if self.call {
            self.restore_all("new CALL_START");
        }
        let inject = if session == 2 {
            self.profile.inject2.clone()
        } else {
            self.profile.inject1.clone()
        };
        let mono = [1i64];
        let on = [1i64];
        let mute = [1i64, self.profile.vsid, self.profile.ramp];
        let mut applied = 0i32;
        let rec = self.profile.rec_config.clone();
        let cap = self.profile.capture.clone();
        applied += self.manage_managed(&rec, &mono, 1, false) as i32;
        applied += self.manage_managed(&cap, &on, 1, false) as i32;
        applied += self.manage_managed(&inject, &on, 1, false) as i32;
        let tx = self.profile.mute_tx.clone();
        let rx = self.profile.mute_rx.clone();
        applied += self.manage_managed(&tx, &mute, 3, true) as i32;
        applied += self.manage_managed(&rx, &mute, 3, true) as i32;
        let controls: Vec<(String, Vec<i64>)> = self
            .call_controls
            .iter()
            .map(|c| (c.name.clone(), c.values.clone()))
            .collect();
        for (name, values) in controls {
            applied += self.manage_managed(&name, &values, values.len(), false) as i32;
        }
        self.call = true;
        self.session = session;
        self.remutes = 0;
        crate::audio::call_begin(self.audio_cfg());
        self.call_orig_save();
        crate::logi!(
            "CALL_START session={}: {} controls applied",
            session,
            applied
        );
        applied
    }

    pub fn call_control_add(&mut self, spec: &str) -> bool {
        let Some(eq) = spec.rfind('=') else {
            return false;
        };
        if eq == 0 {
            return false;
        }
        if self.call_controls.len() >= MAX_CALL_CONTROLS {
            crate::loge!("too many call controls: '{}' dropped", spec);
            return false;
        }
        if eq >= NAME_LEN {
            return false;
        }
        let values = match crate::protocol::parse_call_values(&spec[eq + 1..]) {
            Ok(values) => values,
            Err(CallValuesError::TooMany) => {
                crate::loge!("call control '{}': more than {} values", spec, MAX_VALUES);
                return false;
            }
            Err(CallValuesError::NotANumber) => {
                crate::loge!("call control '{}': not a number", spec);
                return false;
            }
        };
        self.call_controls.push(CallControl {
            name: spec[..eq].to_string(),
            values,
        });
        true
    }

    pub fn silence_add(&mut self, spec: &str) -> bool {
        let Some(eq) = spec.rfind('=') else {
            return false;
        };
        if eq == 0 || eq >= NAME_LEN {
            return false;
        }
        let name = &spec[..eq];
        let raw = &spec[eq + 1..];
        let (value, used) = c_strtoll(raw);
        if used == 0 || used != raw.len() {
            crate::loge!("silence '{}': not a number", spec);
            return false;
        }
        if let Some(i) = self.perm.iter().position(|m| m.name == name) {
            let ctl = self.ctl;
            let safe = self.safe;
            let crumb = &mut self.crumb;
            let m = &mut self.perm[i];
            if ctl_write(ctl, safe, crumb, m, &[value], 1) {
                m.want[0] = value;
            }
            return true;
        }
        self.manage_perm(name, &[value], 1, false)
    }

    pub fn set_key(&mut self, key: &str, val: &str) -> bool {
        if let Some(dst) = self.name_key(key) {
            set_name(dst, val);
            return true;
        }
        match key {
            "call" => self.call_control_add(val),
            "call.clear" => {
                self.call_controls.clear();
                true
            }
            "silence" => self.silence_add(val),
            _ => self.number_key(key, val),
        }
    }

    /// The profile field a control-name key sets.
    fn name_key(&mut self, key: &str) -> Option<&mut String> {
        let p = &mut self.profile;
        Some(match key {
            "capture" => &mut p.capture,
            "rec_config" => &mut p.rec_config,
            "inject.1" => &mut p.inject1,
            "inject.2" => &mut p.inject2,
            "mute.tx" => &mut p.mute_tx,
            "mute.rx" => &mut p.mute_rx,
            _ => return None,
        })
    }

    /// A number key; false for an unknown key.
    fn number_key(&mut self, key: &str, val: &str) -> bool {
        let p = &mut self.profile;
        match key {
            "mute.vsid" => p.vsid = c_strtoll_prefix(val),
            "mute.ramp" => p.ramp = c_strtoll_prefix(val),
            "loopback" => p.loopback = c_atoi(val) != 0,
            _ => {
                let Some(dst) = int_key(p, key) else {
                    return false;
                };
                *dst = c_atoi(val);
            }
        }
        true
    }

    pub fn status_names(&self) -> String {
        let mut out = String::new();
        for m in &self.managed {
            let piece = format!("{};", m.name);
            if out.len() + piece.len() >= 1800 {
                break;
            }
            out.push_str(&piece);
        }
        out
    }

    pub fn call_orig_save(&mut self) {
        let tmp = format!("{}.tmp", CALL_ORIG);
        let Ok(mut f) = std::fs::File::create(&tmp) else {
            crate::logw!("save {}: {}", CALL_ORIG, platform::errstr());
            return;
        };
        let mut out = format!("{}\n", self.crumb.boot_line());
        let mut n = 0usize;
        for m in self.managed.iter().filter(|m| !m.write_only && m.have_orig) {
            out.push_str(&call_orig_line(m));
            n += 1;
        }
        // A full disk must not rename a truncated file into place.
        let ok = f.write_all(out.as_bytes()).is_ok() && f.sync_all().is_ok();
        drop(f);
        if !ok || n == 0 || std::fs::rename(&tmp, CALL_ORIG).is_err() {
            if !ok {
                crate::logw!("save {} failed", CALL_ORIG);
            }
            let _ = std::fs::remove_file(&tmp);
        }
    }

    pub fn call_orig_restore(&mut self) {
        let Ok(text) = std::fs::read_to_string(CALL_ORIG) else {
            return;
        };
        let boot = format!("{}\n", self.crumb.boot_line());
        let mut chunks = text.split_inclusive('\n');
        if chunks.next() != Some(boot.as_str()) {
            crate::logi!("call originals from another boot: dropped");
            let _ = std::fs::remove_file(CALL_ORIG);
            return;
        }
        let mut saved: Vec<(String, Vec<i64>)> = Vec::new();
        for chunk in chunks {
            if saved.len() >= MAX_MANAGED {
                break;
            }
            let Some(line) = chunk.strip_suffix('\n') else {
                break;
            };
            if line.is_empty() {
                continue;
            }
            let Some(tab) = line.rfind('\t') else {
                continue;
            };
            if tab == 0 {
                continue;
            }
            let name = &line[..tab];
            if name.len() >= NAME_LEN {
                continue;
            }
            let Some(vals) = parse_call_orig_values(&line[tab + 1..]) else {
                continue;
            };
            saved.push((name.to_string(), vals));
        }
        crate::logw!(
            "a previous run died mid-call: restoring {} call control(s)",
            saved.len()
        );
        for (name, vals) in saved.into_iter().rev() {
            self.set_by_name(&name, &vals, vals.len());
        }
        let _ = std::fs::remove_file(CALL_ORIG);
    }

    pub fn on_ctl_event(&mut self, numid: u32) {
        let ctl = self.ctl;
        let safe = self.safe;
        let crumb = &mut self.crumb;
        reapply_in(ctl, safe, crumb, &mut self.managed, numid);
        let ctl = self.ctl;
        let safe = self.safe;
        let crumb = &mut self.crumb;
        reapply_in(ctl, safe, crumb, &mut self.perm, numid);
    }

    pub fn is_device_mute(&self, numid: u32) -> bool {
        self.managed
            .iter()
            .any(|m| m.write_only && m.numid == numid)
    }

    pub fn reassert_device_mutes(&mut self) {
        for i in 0..self.managed.len() {
            if !self.managed[i].write_only {
                continue;
            }
            let want = self.managed[i].want;
            let n = self.managed[i].nwant;
            let ctl = self.ctl;
            let safe = self.safe;
            let crumb = &mut self.crumb;
            let m = &mut self.managed[i];
            ctl_write(ctl, safe, crumb, m, &want, n);
        }
        self.remutes += 1;
        if self.remutes <= 20 || self.remutes.is_multiple_of(100) {
            crate::logi!(
                "device mutes re-asserted after a route change (#{})",
                self.remutes
            );
        }
    }

    pub fn drain_ctl_events(&mut self) {
        let mut route_changed = false;
        for _ in 0..64 {
            if self.ctl < 0 {
                break;
            }
            let mut ev: ElemEvent = unsafe { std::mem::zeroed() };
            let n = unsafe {
                libc::read(
                    self.ctl,
                    &mut ev as *mut ElemEvent as *mut libc::c_void,
                    std::mem::size_of::<ElemEvent>(),
                )
            };
            if n != std::mem::size_of::<ElemEvent>() as isize {
                break;
            }
            if ev.type_ == EVENT_ELEM {
                let elem = unsafe { ev.data.elem };
                if elem.mask & EVENT_MASK_VALUE != 0 && elem.mask != EVENT_MASK_REMOVE {
                    self.on_ctl_event(elem.id.numid);
                    if self.call && !self.is_device_mute(elem.id.numid) {
                        route_changed = true;
                    }
                }
            }
        }
        if route_changed {
            self.reassert_device_mutes();
        }
    }

    pub fn inventory(&self, path: &str) -> Result<i32, i32> {
        if self.ctl < 0 {
            return Err(-1);
        }
        let mut list: ElemList = unsafe { std::mem::zeroed() };
        if ioctl_ptr(self.ctl, CTL_ELEM_LIST, &mut list) != 0 {
            return Err(-1);
        }
        let total = list.count;
        let mut ids = vec![ElemId::zeroed(); total.max(1) as usize];
        list.space = total;
        list.pids = ids.as_mut_ptr();
        if ioctl_ptr(self.ctl, CTL_ELEM_LIST, &mut list) != 0 {
            return Err(-1);
        }
        let used = list.used.min(total);
        let tmp = format!("{}.tmp", path);
        let mut f = match std::fs::File::create(&tmp) {
            Ok(f) => f,
            Err(_) => return Err(-2),
        };
        let mut out = String::new();
        let mut n = 0i32;
        for id in ids.iter().take(used as usize) {
            let Some(info) = ctl_info_numid(self.ctl, id.numid) else {
                continue;
            };
            out.push_str(&inventory_line(self.ctl, &info));
            n += 1;
        }
        // A full disk must not rename a truncated list into place.
        let ok = f.write_all(out.as_bytes()).is_ok() && f.flush().is_ok();
        drop(f);
        if !ok || std::fs::rename(&tmp, path).is_err() {
            crate::loge!("inventory: writing {} failed", tmp);
            let _ = std::fs::remove_file(&tmp);
            return Err(-2);
        }
        crate::logi!("inventory: {} controls -> {}", n, path);
        Ok(n)
    }

    pub fn has(&self, name: &str) -> Option<(u32, String)> {
        let info = ctl_info(self.ctl, name)?;
        let items = if info.type_ == ELEM_TYPE_ENUMERATED {
            enum_items(self.ctl, &info, 1500)
        } else {
            String::new()
        };
        Some((info.count, items))
    }
}

/// One call-originals line: "<name>\t<v1>,<v2>...\n" with the control's saved values.
fn call_orig_line(m: &Managed) -> String {
    let count = (m.count as usize).min(MAX_VALUES);
    let values: Vec<String> = (0..count)
        .map(|k| {
            if m.enumerated {
                (elem_enum(&m.orig, k) as i64).to_string()
            } else {
                (elem_integer(&m.orig, k) as i64).to_string()
            }
        })
        .collect();
    format!("{}\t{}\n", m.name, values.join(","))
}

/// One inventory line: numid, name, type, count and the range or the enum items, tab-separated.
fn inventory_line(ctl: RawFd, info: &ElemInfo) -> String {
    const TYPES: [&str; 7] = ["none", "bool", "int", "enum", "bytes", "iec958", "int64"];
    let name = kernel_name(&info.id.name);
    let ty = TYPES.get(info.type_ as usize).copied().unwrap_or("?");
    let mut line = format!("{}\t{}\t{}\t{}\t", info.id.numid, name, ty, info.count);
    match info.type_ {
        ELEM_TYPE_INTEGER => {
            let (min, max) = info_integer(info);
            line.push_str(&format!("{}..{}", min, max));
        }
        ELEM_TYPE_INTEGER64 => {
            let (min, max) = unsafe { (info.value.integer64.min, info.value.integer64.max) };
            line.push_str(&format!("{}..{}", min, max));
        }
        ELEM_TYPE_ENUMERATED => {
            line.push_str(&enum_items(ctl, info, 64 * NAME_LEN));
            let count = info_enum_items(info);
            if count > 64 {
                line.push_str(&format!("|...({})", count));
            }
        }
        _ => {}
    }
    line.push('\n');
    line
}

fn create_0600(path: &str) -> std::io::Result<std::fs::File> {
    std::fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .open(path)
}

/// A control to take over: its name, the values to hold and whether it is written without being read back.
struct Want<'a> {
    name: &'a str,
    values: &'a [i64],
    n: usize,
    write_only: bool,
}

/// "-" clears a control name; any other value is cut to NAME_LEN - 1 bytes at a char boundary.
fn set_name(dst: &mut String, val: &str) {
    if val == "-" {
        dst.clear();
        return;
    }
    let mut n = (NAME_LEN - 1).min(val.len());
    while !val.is_char_boundary(n) {
        n -= 1;
    }
    *dst = val[..n].to_string();
}

/// The profile field an atoi key sets.
fn int_key<'a>(p: &'a mut Profile, key: &str) -> Option<&'a mut i32> {
    Some(match key {
        "capture.pcm" => &mut p.capture_pcm,
        "inject.pcm" => &mut p.inject_pcm,
        "capture.rate" => &mut p.capture_rate,
        "capture.channels" => &mut p.capture_channels,
        "inject.rate" => &mut p.inject_rate,
        "inject.channels" => &mut p.inject_channels,
        _ => return None,
    })
}

fn manage_in(
    ctl: RawFd,
    safe: bool,
    crumb: &mut Crumb,
    list: &mut Vec<Managed>,
    other: &[Managed],
    w: &Want,
) -> bool {
    let (name, want, n, write_only) = (w.name, w.values, w.n, w.write_only);
    let Some(info) = admit(ctl, list.len(), other, w) else {
        return false;
    };
    let mut m = Managed::empty(name, info.id.numid, info);
    m.write_only = write_only;
    m.nwant = n;
    m.want[..n].copy_from_slice(&want[..n]);
    if !write_only {
        if let Some(orig) = ctl_read(ctl, m.numid) {
            m.orig = orig;
            m.have_orig = true;
        }
    }
    ctl_write(ctl, safe, crumb, &mut m, want, n);
    crate::logi!(
        "managed '{}' numid={} -> {} (orig {})",
        name,
        m.numid,
        want.first().copied().unwrap_or(0),
        orig_first(&m)
    );
    list.push(m);
    true
}

/// The control's info when `w` may be managed: 1..MAX_VALUES values, the control exists, a slot
/// is free, the values fit the control, and no other list holds it at other values.
fn admit(ctl: RawFd, held: usize, other: &[Managed], w: &Want) -> Option<ElemInfo> {
    let (name, want, n) = (w.name, w.values, w.n);
    if name.is_empty() {
        return None;
    }
    if !(1..=MAX_VALUES).contains(&n) {
        crate::loge!(
            "'{}': {} value(s) (max {}): not managed",
            name,
            n,
            MAX_VALUES
        );
        return None;
    }
    let Some(info) = ctl_info(ctl, name) else {
        crate::logw!("no control '{}'", name);
        return None;
    };
    if held == MAX_MANAGED {
        crate::loge!("too many managed controls");
        return None;
    }
    if !value_ok(name, &info, want, n) {
        return None;
    }
    let clash = other
        .iter()
        .any(|m| m.numid == info.id.numid && m.want[..n] != want[..n]);
    if clash {
        crate::loge!(
            "'{}' is already held at another value: not managed twice",
            name
        );
        return None;
    }
    Some(info)
}

/// The first original value, for the log (-1 when none was read).
fn orig_first(m: &Managed) -> i64 {
    if !m.have_orig {
        -1
    } else if m.enumerated {
        elem_enum(&m.orig, 0) as i64
    } else {
        elem_integer(&m.orig, 0) as i64
    }
}

fn ctl_write_value(
    ctl: RawFd,
    safe: bool,
    crumb: &mut Crumb,
    m: &mut Managed,
    v: &mut ElemValue,
) -> bool {
    if safe {
        return false;
    }
    v.id.numid = m.numid;
    m.last_write_ns = platform::now_ns(libc::CLOCK_MONOTONIC);
    if ctl < 0 {
        crate::loge!("write '{}': no mixer", m.name);
        return false;
    }
    crumb.arm();
    if !crate::alsa::ctl_write(ctl, v) {
        crate::loge!("write '{}' failed: {}", m.name, platform::errstr());
        return false;
    }
    true
}

fn ctl_write(
    ctl: RawFd,
    safe: bool,
    crumb: &mut Crumb,
    m: &mut Managed,
    vals: &[i64],
    n: usize,
) -> bool {
    if m.info.count != 0 && !value_ok(&m.name, &m.info, vals, n) {
        return false;
    }
    let mut v: ElemValue = unsafe { std::mem::zeroed() };
    v.id.numid = m.numid;
    if !m.write_only && ctl >= 0 {
        if let Some(read) = ctl_read(ctl, m.numid) {
            v = read;
        }
    }
    for (i, &val) in vals.iter().enumerate().take(n.min(MAX_VALUES)) {
        if m.enumerated {
            set_elem_enum(&mut v, i, val as u32);
        } else {
            set_elem_integer(&mut v, i, val as c_long);
        }
    }
    ctl_write_value(ctl, safe, crumb, m, &mut v)
}

fn ctl_restore(ctl: RawFd, safe: bool, crumb: &mut Crumb, m: &mut Managed) -> bool {
    if !m.have_orig {
        return false;
    }
    let mut v = m.orig;
    ctl_write_value(ctl, safe, crumb, m, &mut v)
}

fn reapply_in(ctl: RawFd, safe: bool, crumb: &mut Crumb, list: &mut [Managed], numid: u32) {
    for m in list.iter_mut().filter(|m| m.numid == numid) {
        if !lost_value(ctl, m) || !reapply_allowed(m) {
            continue;
        }
        crate::logw!("'{}' rewritten by someone else, re-applying", m.name);
        let want = m.want;
        let n = m.nwant;
        ctl_write(ctl, safe, crumb, m, &want, n);
    }
}

/// The control changed under us: a write-only control after our own write's grace period (it
/// cannot be read back), a readable one when it no longer holds our values or cannot be read.
fn lost_value(ctl: RawFd, m: &Managed) -> bool {
    if m.write_only {
        return platform::now_ns(libc::CLOCK_MONOTONIC) - m.last_write_ns >= OWN_WRITE_GRACE_NS;
    }
    let Some(read) = ctl_read(ctl, m.numid) else {
        return true;
    };
    (0..m.nwant).any(|i| {
        let cur = if m.enumerated {
            elem_enum(&read, i) as i64
        } else {
            elem_integer(&read, i) as i64
        };
        cur != m.want[i]
    })
}

/// At most 5 re-applies in one second; a 6th leaves the control alone for 10 s.
fn reapply_allowed(m: &mut Managed) -> bool {
    let now = platform::now_sec();
    if now < m.paused_until_sec {
        return false;
    }
    if now != m.reapply_sec {
        m.reapply_sec = now;
        m.reapplies = 0;
    }
    m.reapplies += 1;
    if m.reapplies > 5 {
        m.paused_until_sec = now + 10;
        crate::loge!("'{}' won't keep our value: left alone for 10 s", m.name);
        return false;
    }
    true
}
