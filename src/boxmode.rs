// Box mode: screen and role control, battery care, network block, the volume-key escape gesture,
// the persistent log capture and the host broadcasts. GPL-3.0-or-later.
#![cfg(target_os = "android")]

use std::os::unix::io::RawFd;

use libc::{c_int, c_void, pid_t};

use crate::protocol::{
    c_atoi, charge_band, charge_status_line, net_cycle_script, netblock_script, valid_pkg, valid_role, EscapeGesture,
};

pub const BOX_MAX_KEYDEVS: usize = 32;

const ESCAPE_ACTION: &str = "com.keremgok.hermessim.host.BOX_ESCAPE";
const ESCAPE_RECEIVER: &str = "com.keremgok.hermessim.host/.DaemonEventReceiver";
const DAEMON_UP: &str = "com.keremgok.hermessim.host.DAEMON_UP";

const CUT_NODES: [(&str, bool); 10] = [
    ("/sys/class/power_supply/battery/batt_slate_mode", true),
    ("/sys/class/power_supply/battery/charging_enabled", false),
    (
        "/sys/class/power_supply/battery/battery_charging_enabled",
        false,
    ),
    ("/sys/class/power_supply/battery/charge_disable", true),
    ("/sys/class/power_supply/battery/input_suspend", true),
    ("/sys/class/qcom-battery/input_suspend", true),
    ("/sys/class/power_supply/usb/input_suspend", true),
    ("/sys/class/power_supply/main/input_suspend", true),
    ("/sys/class/power_supply/ac/input_suspend", true),
    ("/sys/class/power_supply/dc/input_suspend", true),
];
const CAP_NODES: [&str; 6] = [
    "/sys/class/power_supply/battery/batt_full_capacity",
    "/sys/class/power_supply/battery/charge_control_end_threshold",
    "/sys/class/power_supply/battery/charge_stop_level",
    "/sys/class/power_supply/main/charge_control_end_threshold",
    "/sys/class/power_supply/bms/charge_control_end_threshold",
    "/sys/class/power_supply/lenovo_battery/charge_control_end_threshold",
];
const START_NODES: [&str; 4] = [
    "/sys/class/power_supply/battery/charge_control_start_threshold",
    "/sys/class/power_supply/main/charge_control_start_threshold",
    "/sys/class/power_supply/bms/charge_control_start_threshold",
    "/sys/class/power_supply/lenovo_battery/charge_control_start_threshold",
];
const BATT_CAPACITY: &str = "/sys/class/power_supply/battery/capacity";
const LIMIT_DIR: &str = "/data/adb/hermessim";
const LIMIT_FILE: &str = "/data/adb/hermessim/charge_limit";
const ORIGINAL_FILE: &str = "/data/adb/hermessim/charge_original";
const MIN_ON_BATTERY: i32 = 15;
const CHARGE_MIN: i32 = 50;
const RECUT_MAX: i32 = 6;

const LOG_FILE: &str = "/data/adb/hermessim/logs/host.log";
const LOG_PIDF: &str = "/data/adb/hermessim/logs/logcat.pid";

const NB_MAX: usize = 16;

const EV_KEY: u16 = 0x01;
const EV_ABS: u16 = 0x03;
const KEY_VOLUMEDOWN: u16 = 114;
const KEY_VOLUMEUP: u16 = 115;
const KEY_POWER: u16 = 116;

fn spawn(argv: &[&str]) -> bool {
    match crate::platform::spawn(argv) {
        Ok(_) => true,
        Err(e) => {
            crate::logw!("spawn {}: {}", argv[0], crate::platform::err_text(&e));
            false
        }
    }
}

pub fn screen(on: bool) -> i32 {
    let argv = [
        "/system/bin/input",
        "keyevent",
        if on {
            "KEYCODE_WAKEUP"
        } else {
            "KEYCODE_SLEEP"
        },
    ];
    if spawn(&argv) {
        0
    } else {
        -1
    }
}

pub fn role(role: &str, pkg: &str) -> i32 {
    if !valid_role(role) || !valid_pkg(pkg) {
        return -1;
    }
    let argv = ["/system/bin/cmd", "role", "add-role-holder", role, pkg, "0"];
    crate::logi!("role {} -> {}", role, pkg);
    if spawn(&argv) {
        0
    } else {
        -1
    }
}

fn mkdir_mode(path: &str, mode: u32) {
    if let Ok(c) = std::ffi::CString::new(path) {
        unsafe {
            libc::mkdir(c.as_ptr(), mode as libc::mode_t);
        }
    }
}

fn node_writable(path: &str) -> bool {
    use std::os::unix::fs::PermissionsExt;
    std::fs::metadata(path)
        .map(|m| m.permissions().mode() & 0o200 != 0)
        .unwrap_or(false)
}

fn first_writable(nodes: &[&'static str]) -> Option<&'static str> {
    nodes.iter().copied().find(|p| node_writable(p))
}

fn read_int(path: &str) -> i32 {
    match std::fs::read(path) {
        Ok(bytes) if !bytes.is_empty() => c_atoi(&String::from_utf8_lossy(&bytes)),
        _ => -1,
    }
}

fn write_int(path: &str, value: i32) -> i32 {
    write_text(path, &value.to_string())
}

fn read_text(path: &str) -> Option<String> {
    let bytes = std::fs::read(path).ok()?;
    if bytes.is_empty() {
        return None;
    }
    let mut s = String::from_utf8_lossy(&bytes).into_owned();
    while s.ends_with('\n') || s.ends_with('\r') || s.ends_with(' ') {
        s.pop();
    }
    Some(s)
}

fn write_text(path: &str, value: &str) -> i32 {
    use std::io::Write;
    match std::fs::OpenOptions::new().write(true).open(path) {
        Ok(mut f) => {
            if f.write_all(value.as_bytes()).is_ok() {
                0
            } else {
                -1
            }
        }
        Err(_) => -1,
    }
}

fn write_file(path: &str, value: &str) -> i32 {
    use std::io::Write;
    match std::fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .open(path)
    {
        Ok(mut f) => {
            if f.write_all(value.as_bytes()).is_ok() {
                0
            } else {
                -1
            }
        }
        Err(_) => -1,
    }
}

fn cap_node() -> Option<&'static str> {
    first_writable(&CAP_NODES)
}

struct Charge {
    limit: i32,
    resume: i32,
    drain: i32,
    cutting: bool,
    cut_failed: bool,
    cap_original: i32,
    start_original: i32,
    originals_loaded: bool,
    behaviour_path: String,
    behaviour_force: bool,
    behaviour_scanned: bool,
    behaviour_failed: bool,
    recut_window: i64,
    recuts: i32,
    recut_paused_until: i64,
}

impl Charge {
    fn new() -> Self {
        Self {
            limit: 0,
            resume: 0,
            drain: 0,
            cutting: false,
            cut_failed: false,
            cap_original: -1,
            start_original: -1,
            originals_loaded: false,
            behaviour_path: String::new(),
            behaviour_force: false,
            behaviour_scanned: false,
            behaviour_failed: false,
            recut_window: -1,
            recuts: 0,
            recut_paused_until: 0,
        }
    }

    fn find_behaviour(&mut self) {
        if self.behaviour_scanned {
            return;
        }
        self.behaviour_scanned = true;
        self.behaviour_path.clear();
        self.behaviour_force = false;
        let Ok(dir) = std::fs::read_dir("/sys/class/power_supply") else {
            return;
        };
        for entry in dir.flatten() {
            let name = entry.file_name();
            let Some(name) = name.to_str() else { continue };
            if name.starts_with('.') {
                continue;
            }
            let path = format!("/sys/class/power_supply/{}/charge_behaviour", name);
            if !node_writable(&path) {
                continue;
            }
            let Some(value) = read_text(&path) else {
                continue;
            };
            if !value.contains("inhibit-charge") {
                continue;
            }
            self.behaviour_path = path.clone();
            self.behaviour_force = value.contains("force-discharge");
            crate::logi!(
                "battery care: {} offers {}",
                path,
                if self.behaviour_force {
                    "inhibit-charge + force-discharge"
                } else {
                    "inhibit-charge"
                }
            );
            break;
        }
    }

    fn behaviour_active(&self, wanted: &str) -> bool {
        let Some(value) = read_text(&self.behaviour_path) else {
            return false;
        };
        if let Some(left) = value.find('[') {
            if let Some(right_rel) = value[left..].find(']') {
                let right = left + right_rel;
                if right > left + 1 {
                    return &value[left + 1..right] == wanted;
                }
            }
        }
        value == wanted
    }

    fn set_behaviour(&mut self, value: &str) -> i32 {
        if self.behaviour_path.is_empty() {
            return 0;
        }
        if self.behaviour_active(value) {
            return 0;
        }
        if write_text(&self.behaviour_path, value) < 0 {
            if !self.behaviour_failed {
                crate::logw!(
                    "battery care: writing {}={} failed: {}",
                    self.behaviour_path,
                    value,
                    crate::platform::errstr()
                );
            }
            self.behaviour_failed = true;
            return -1;
        }
        self.behaviour_failed = false;
        crate::logi!(
            "battery care: charge_behaviour={} ({})",
            value,
            self.behaviour_path
        );
        0
    }

    fn charger_online() -> i32 {
        const NODES: [&str; 4] = [
            "/sys/class/power_supply/usb/online",
            "/sys/class/power_supply/ac/online",
            "/sys/class/power_supply/dc/online",
            "/sys/class/power_supply/pc_port/online",
        ];
        let mut known = false;
        for path in NODES {
            let value = read_int(path);
            if value < 0 {
                continue;
            }
            known = true;
            if value > 0 {
                return 1;
            }
        }
        if known {
            0
        } else {
            -1
        }
    }

    fn update_band(&mut self) {
        let (resume, drain) = charge_band(self.limit);
        self.resume = resume;
        self.drain = drain;
    }

    fn cut_index(&self) -> i32 {
        for (i, (path, _)) in CUT_NODES.iter().enumerate() {
            if node_writable(path) {
                return i as i32;
            }
        }
        -1
    }

    fn cut_node(&self) -> Option<&'static str> {
        let i = self.cut_index();
        if i < 0 {
            None
        } else {
            Some(CUT_NODES[i as usize].0)
        }
    }

    fn set_cut(&mut self, on: bool) {
        let i = self.cut_index();
        if i < 0 {
            return;
        }
        let (node, stop) = CUT_NODES[i as usize];
        let want = if on { stop } else { !stop };
        if read_int(node) == want as i32 {
            self.cutting = on;
            return;
        }
        if write_int(node, want as i32) < 0 {
            if !self.cut_failed {
                crate::logw!(
                    "battery care: writing {} failed: {}",
                    node,
                    crate::platform::errstr()
                );
            }
            self.cut_failed = true;
            return;
        }
        self.cut_failed = false;
        if self.cutting != on {
            crate::logi!(
                "battery care: {} ({})",
                if on {
                    "charging stopped"
                } else {
                    "charging allowed"
                },
                node
            );
        }
        self.cutting = on;
    }

    fn save_originals(&self) {
        mkdir_mode(LIMIT_DIR, 0o700);
        write_file(
            ORIGINAL_FILE,
            &format!("{} {}\n", self.cap_original, self.start_original),
        );
    }

    fn load_originals(&mut self) {
        if self.originals_loaded {
            return;
        }
        self.originals_loaded = true;
        let Some(text) = read_text(ORIGINAL_FILE) else {
            return;
        };
        let mut parts = text.split_whitespace();
        let (Some(cap), Some(start)) = (parts.next(), parts.next()) else {
            return;
        };
        let (Ok(cap), Ok(start)) = (cap.parse::<i32>(), start.parse::<i32>()) else {
            return;
        };
        if (0..=100).contains(&cap) && (0..=100).contains(&start) {
            self.cap_original = cap;
            self.start_original = start;
        }
    }

    fn capture_originals(&mut self) {
        self.load_originals();
        let mut changed = false;
        if self.cap_original < 0 {
            let value = cap_node().map(read_int).unwrap_or(-1);
            if (0..=100).contains(&value) {
                self.cap_original = value;
                changed = true;
            }
        }
        if self.start_original < 0 {
            let value = first_writable(&START_NODES).map(read_int).unwrap_or(-1);
            if (0..=100).contains(&value) {
                self.start_original = value;
                changed = true;
            }
        }
        if changed {
            self.save_originals();
        }
    }

    fn release_cut(&mut self) {
        self.find_behaviour();
        self.set_behaviour("auto");
        if self.cut_node().is_some() {
            self.set_cut(false);
        }
        self.cutting = false;
    }

    fn release_charge(&mut self) {
        self.release_cut();
        let cap = cap_node();
        let start = first_writable(&START_NODES);
        if let Some(cap) = cap {
            if self.cap_original >= 0 && read_int(cap) != self.cap_original {
                write_int(cap, self.cap_original);
            }
        }
        if let Some(start) = start {
            if self.start_original >= 0 && read_int(start) != self.start_original {
                write_int(start, self.start_original);
            }
        }
    }

    fn node_name(&mut self) -> Option<&'static str> {
        self.find_behaviour();
        if !self.behaviour_path.is_empty() {
            return Some(if self.behaviour_force {
                "behaviour+force"
            } else {
                "behaviour"
            });
        }
        let cap = cap_node();
        let cut = self.cut_node();
        match (cap.is_some(), cut.is_some()) {
            (true, true) => Some("cap+cut"),
            (true, false) => Some("cap"),
            (false, true) => Some("cut"),
            _ => None,
        }
    }

    fn status(&mut self) -> String {
        self.find_behaviour();
        let behaviour_held = !self.behaviour_path.is_empty()
            && (self.behaviour_active("inhibit-charge") || self.behaviour_active("force-discharge"));
        let held = self.cutting || behaviour_held;
        let stuck = self.recut_paused_until != 0
            && crate::platform::boot_seconds() < self.recut_paused_until;
        charge_status_line(self.limit, self.resume, self.drain, self.cutting, held, stuck)
    }

    fn check(&mut self) {
        self.find_behaviour();
        let level = read_int(BATT_CAPACITY);
        if level < 0 {
            return;
        }
        if self.limit <= 0 || self.limit >= 100 {
            return;
        }
        if level <= MIN_ON_BATTERY {
            self.release_cut();
            return;
        }

        let online = Self::charger_online();
        let plugged = online != 0;
        let cut = self.cut_index();

        if !self.behaviour_path.is_empty() {
            if level > self.drain && plugged && self.behaviour_force {
                self.set_cut(false);
                if self.set_behaviour("force-discharge") == 0 {
                    return;
                }
            }
            if level > self.drain && plugged && cut >= 0 {
                self.set_behaviour("auto");
                self.set_cut(true);
                return;
            }
            if self.cutting {
                self.set_cut(false);
            }
            if level >= self.limit {
                self.set_behaviour("inhibit-charge");
            } else if level <= self.resume {
                self.set_behaviour("auto");
            }
            return;
        }

        let cap = cap_node();
        if let Some(cap) = cap {
            if read_int(cap) != self.limit {
                write_int(cap, self.limit);
            }
        }
        if cut < 0 {
            return;
        }
        let now = crate::platform::boot_seconds();
        if self.recut_paused_until != 0 {
            if now < self.recut_paused_until {
                return;
            }
            self.recut_paused_until = 0;
        }
        let with_cap = cap.is_some();
        let release_at = if with_cap { self.limit } else { self.resume };
        let should_cut = if with_cap {
            plugged && level > self.drain
        } else {
            plugged && level >= self.limit
        };
        let (node, stop) = CUT_NODES[cut as usize];
        if self.cutting && read_int(node) != stop as i32 {
            self.cutting = false;
            if level <= release_at {
                return;
            }
            if self.recut_window < 0 || now - self.recut_window >= 3600 {
                self.recut_window = now;
                self.recuts = 0;
            }
            if self.recuts >= RECUT_MAX {
                crate::logw!(
                    "battery care: {} keeps being reset, leaving it this hour",
                    node
                );
                self.recut_paused_until = self.recut_window + 3600;
                return;
            }
            self.recuts += 1;
            crate::logw!(
                "battery care: the kernel reset {}, cutting again ({} this hour)",
                node,
                self.recuts
            );
            self.set_cut(true);
            return;
        }
        if !self.cutting && should_cut {
            self.set_cut(true);
        } else if self.cutting && level <= release_at {
            self.set_cut(false);
        }
    }

    fn apply(&mut self, percent: i32) -> i32 {
        if !(CHARGE_MIN..=100).contains(&percent) {
            return -1;
        }
        self.behaviour_scanned = false;
        self.find_behaviour();
        if self.node_name().is_none() {
            return -2;
        }
        self.limit = if percent >= 100 { 0 } else { percent };
        self.update_band();
        self.capture_originals();
        if self.limit == 0 {
            self.release_charge();
            mkdir_mode(LIMIT_DIR, 0o700);
            write_file(LIMIT_FILE, "100\n");
            crate::logi!("battery care: off");
            return 0;
        }
        let start = first_writable(&START_NODES);
        let cap = cap_node();
        let new_end = self.limit;
        let new_start = self.resume;
        if let Some(cap) = cap {
            let old_end = read_int(cap);
            match start {
                // Raising the band: the end goes up first, so the start is never above the end.
                Some(start) if old_end >= 0 && new_start > old_end => {
                    write_int(cap, new_end);
                    write_int(start, new_start);
                }
                _ => {
                    if let Some(start) = start {
                        write_int(start, new_start);
                    }
                    write_int(cap, new_end);
                }
            }
        } else if let Some(start) = start {
            write_int(start, new_start);
        }
        mkdir_mode(LIMIT_DIR, 0o700);
        if write_file(LIMIT_FILE, &format!("{}\n", percent)) < 0 {
            crate::logw!("save {}: {}", LIMIT_FILE, crate::platform::errstr());
        }
        let node = self.node_name().unwrap_or("?");
        crate::logi!(
            "battery care: target {}% (band {}-{}, {})",
            percent,
            self.resume,
            self.drain,
            node
        );
        self.check();
        0
    }

    fn init(&mut self) {
        self.capture_originals();
        self.release_charge();
        let saved = read_int(LIMIT_FILE);
        if (CHARGE_MIN..=100).contains(&saved) {
            self.apply(saved);
        }
    }

    fn shutdown(&mut self) {
        self.release_charge();
    }
}

#[derive(Clone, Copy)]
struct Nb {
    uid: i32,
    want: i32,
    done: i32,
}

#[repr(C)]
struct InputEvent {
    time: libc::timeval,
    type_: u16,
    code: u16,
    value: i32,
}

pub struct BoxState {
    keys: [RawFd; BOX_MAX_KEYDEVS],
    power: [bool; BOX_MAX_KEYDEVS],
    grabbed: [bool; BOX_MAX_KEYDEVS],
    volup: [bool; BOX_MAX_KEYDEVS],
    voldown: [bool; BOX_MAX_KEYDEVS],
    nkeys: usize,
    grab_until_sec: i64,
    gesture: EscapeGesture,
    charge: Charge,
    nb: Vec<Nb>,
    nb_pid: pid_t,
    nb_running: i32,
    nb_running_on: i32,
    cycle_pid: pid_t,
    log_started_sec: i64,
    log_last_sec: i64,
}

impl BoxState {
    pub fn new() -> Self {
        Self {
            keys: [-1; BOX_MAX_KEYDEVS],
            power: [false; BOX_MAX_KEYDEVS],
            grabbed: [false; BOX_MAX_KEYDEVS],
            volup: [false; BOX_MAX_KEYDEVS],
            voldown: [false; BOX_MAX_KEYDEVS],
            nkeys: 0,
            grab_until_sec: 0,
            gesture: EscapeGesture::default(),
            charge: Charge::new(),
            nb: Vec::new(),
            nb_pid: 0,
            nb_running: -1,
            nb_running_on: 0,
            cycle_pid: 0,
            log_started_sec: 0,
            log_last_sec: 0,
        }
    }

    // -------------------------------------------------------------- battery care

    pub fn charge_init(&mut self) {
        self.charge.init();
    }

    pub fn charge_check(&mut self) {
        self.charge.check();
    }

    pub fn charge_shutdown(&mut self) {
        self.charge.shutdown();
    }

    pub fn charge_node(&mut self) -> Option<&'static str> {
        self.charge.node_name()
    }

    pub fn charge_status(&mut self) -> String {
        self.charge.status()
    }

    pub fn charge(&mut self, percent: i32) -> i32 {
        self.charge.apply(percent)
    }

    // -------------------------------------------------------------- uevents

    pub fn uevent_open() -> RawFd {
        let fd = unsafe {
            libc::socket(
                libc::AF_NETLINK,
                libc::SOCK_DGRAM | libc::SOCK_CLOEXEC | libc::SOCK_NONBLOCK,
                libc::NETLINK_KOBJECT_UEVENT,
            )
        };
        if fd < 0 {
            crate::logw!("uevent socket: {}", crate::platform::errstr());
            return -1;
        }
        let mut a: libc::sockaddr_nl = unsafe { std::mem::zeroed() };
        a.nl_family = libc::AF_NETLINK as libc::sa_family_t;
        a.nl_groups = 1;
        let rc = unsafe {
            libc::bind(
                fd,
                &a as *const libc::sockaddr_nl as *const libc::sockaddr,
                std::mem::size_of::<libc::sockaddr_nl>() as libc::socklen_t,
            )
        };
        if rc < 0 {
            crate::logw!("uevent bind: {}", crate::platform::errstr());
            unsafe { libc::close(fd) };
            return -1;
        }
        fd
    }

    pub fn uevent(&mut self, fd: RawFd) {
        let mut buf = [0u8; 4096];
        let mut power = false;
        loop {
            let n = unsafe { libc::recv(fd, buf.as_mut_ptr() as *mut c_void, buf.len() - 1, 0) };
            if n <= 0 {
                break;
            }
            let n = n as usize;
            let mut i = 0;
            while i < n {
                let end = buf[i..n]
                    .iter()
                    .position(|&b| b == 0)
                    .map(|p| i + p)
                    .unwrap_or(n);
                if &buf[i..end] == b"SUBSYSTEM=power_supply" {
                    power = true;
                }
                i = end + 1;
            }
        }
        if power {
            self.charge_check();
        }
    }

    // -------------------------------------------------------------- network block

    fn nb_next(&mut self) {
        if self.nb_pid > 0 {
            return;
        }
        for k in 0..self.nb.len() {
            if self.nb[k].want == self.nb[k].done {
                continue;
            }
            let uid = self.nb[k].uid;
            let on = self.nb[k].want;
            let cmd = netblock_script(uid, on != 0);
            let argv = ["/system/bin/sh", "-c", cmd.as_str()];
            let pid = match crate::platform::spawn(&argv) {
                Ok(pid) => pid,
                Err(e) => {
                    crate::logw!("spawn {}: {}", argv[0], crate::platform::err_text(&e));
                    crate::logw!("network block: spawn failed");
                    self.nb[k].done = on;
                    continue;
                }
            };
            self.nb_pid = pid;
            self.nb_running = k as i32;
            self.nb_running_on = on;
            crate::logi!(
                "network {} for uid {}",
                if on != 0 { "blocked" } else { "unblocked" },
                uid
            );
            return;
        }
    }

    pub fn reap(&mut self) {
        let mut status: c_int = 0;
        loop {
            let pid = unsafe { libc::waitpid(-1, &mut status, libc::WNOHANG) };
            if pid <= 0 {
                break;
            }
            if pid == self.cycle_pid {
                self.cycle_pid = 0;
                crate::logi!("network cycle done (status {})", status);
                continue;
            }
            if pid != self.nb_pid {
                continue;
            }
            let exited = status & 0x7f == 0;
            let code = (status >> 8) & 0xff;
            if !exited || code != 0 {
                let uid = self
                    .nb
                    .get(self.nb_running as usize)
                    .map(|n| n.uid)
                    .unwrap_or(-1);
                crate::logw!(
                    "network block script for uid {} ended with status {}",
                    uid,
                    status
                );
            }
            if let Some(slot) = self.nb.get_mut(self.nb_running as usize) {
                slot.done = self.nb_running_on;
            }
            self.nb_pid = 0;
            self.nb_running = -1;
        }
        self.nb_next();
    }

    /// Airplane mode on for 3-30 s, then off, from a detached script. 0 ok, -1 failed, -2 one running.
    pub fn net_cycle(&mut self, seconds: i32) -> i32 {
        let Some(script) = net_cycle_script(seconds) else {
            return -1;
        };
        if self.cycle_pid > 0 {
            return -2;
        }
        let argv = ["/system/bin/sh", "-c", script.as_str()];
        match crate::platform::spawn(&argv) {
            Ok(pid) => self.cycle_pid = pid,
            Err(e) => {
                crate::logw!("spawn {}: {}", argv[0], crate::platform::err_text(&e));
                return -1;
            }
        }
        crate::logi!("network cycle: airplane mode for {} s", seconds);
        0
    }

    pub fn netblock(&mut self, uid: i32, on: bool) -> i32 {
        if !(10000..=99999).contains(&uid) {
            return -1;
        }
        let on = on as i32;
        let mut k = 0;
        while k < self.nb.len() && self.nb[k].uid != uid {
            k += 1;
        }
        if k == self.nb.len() {
            if self.nb.len() == NB_MAX {
                crate::logw!("network block: more than {} uids", NB_MAX);
                return -1;
            }
            self.nb.push(Nb {
                uid,
                want: on,
                done: -1,
            });
        }
        self.nb[k].want = on;
        if !(self.nb_pid > 0 && self.nb_running == k as i32) {
            self.nb[k].done = -1;
        }
        self.nb_next();
        0
    }

    // -------------------------------------------------------------- escape gesture / keys

    pub fn keys_open(&mut self) -> i32 {
        let Ok(dir) = std::fs::read_dir("/dev/input") else {
            return 0;
        };
        for entry in dir.flatten() {
            if self.nkeys >= BOX_MAX_KEYDEVS {
                break;
            }
            let name = entry.file_name();
            let Some(name) = name.to_str() else { continue };
            if !name.starts_with("event") {
                continue;
            }
            let path = format!("/dev/input/{}", name);
            let Ok(cpath) = std::ffi::CString::new(path.as_str()) else {
                continue;
            };
            let fd = unsafe {
                libc::open(
                    cpath.as_ptr(),
                    libc::O_RDONLY | libc::O_NONBLOCK | libc::O_CLOEXEC,
                )
            };
            if fd < 0 {
                continue;
            }
            let mut keys = [0u64; 16];
            let mut types = [0u64; 4];
            unsafe {
                libc::ioctl(
                    fd,
                    crate::alsa::eviocgbit(0, types.len() * 8) as c_int,
                    types.as_mut_ptr(),
                );
            }
            let keys_rc = unsafe {
                libc::ioctl(
                    fd,
                    crate::alsa::eviocgbit(EV_KEY as u32, keys.len() * 8) as c_int,
                    keys.as_mut_ptr(),
                )
            };
            let has_power = has_bit(&keys, KEY_POWER);
            let has_up = has_bit(&keys, KEY_VOLUMEUP);
            let has_down = has_bit(&keys, KEY_VOLUMEDOWN);
            if !has_bit(&types, EV_ABS) && keys_rc >= 0 && (has_up || has_down || has_power) {
                let mut namebuf = [0u8; 64];
                let name_rc = unsafe {
                    libc::ioctl(
                        fd,
                        crate::alsa::eviocgname(64) as c_int,
                        namebuf.as_mut_ptr(),
                    )
                };
                // A device that won't give its name is logged as "?".
                let dev_name = if name_rc < 0 {
                    "?".to_string()
                } else {
                    crate::platform::kernel_name(&namebuf)
                };
                self.power[self.nkeys] = has_power;
                self.volup[self.nkeys] = has_up;
                self.voldown[self.nkeys] = has_down;
                crate::logi!(
                    "box keys: watching {} ({}){}",
                    path,
                    dev_name,
                    if has_power { " with the power key" } else { "" }
                );
                self.keys[self.nkeys] = fd;
                self.nkeys += 1;
            } else {
                unsafe { libc::close(fd) };
            }
        }
        self.nkeys as i32
    }

    pub fn key_count(&self) -> i32 {
        self.nkeys as i32
    }

    pub fn key_fd(&self, i: usize) -> RawFd {
        if i < self.nkeys {
            self.keys[i]
        } else {
            -1
        }
    }

    pub fn key_drop(&mut self, i: usize) {
        if i >= self.nkeys || self.keys[i] < 0 {
            return;
        }
        crate::logw!("box keys: input device {} gone", i);
        unsafe { libc::close(self.keys[i]) };
        self.keys[i] = -1;
        self.grabbed[i] = false;
    }

    pub fn power_block(&mut self, on: bool) -> i32 {
        if on {
            let mut up = false;
            let mut down = false;
            for i in 0..self.nkeys {
                if self.keys[i] >= 0 {
                    up |= self.volup[i];
                    down |= self.voldown[i];
                }
            }
            if !up || !down {
                crate::logw!("power key not blocked: the volume keys aren't both watched (no escape gesture)");
                return -1;
            }
            self.grab_until_sec = crate::platform::now_sec() + 15 * 60;
        } else {
            self.grab_until_sec = 0;
        }
        let mut n = 0;
        for i in 0..self.nkeys {
            if self.keys[i] < 0 || !self.power[i] || self.grabbed[i] == on {
                continue;
            }
            let rc = unsafe {
                libc::ioctl(
                    self.keys[i],
                    crate::alsa::EVIOCGRAB as c_int,
                    if on { 1 as c_int } else { 0 as c_int },
                )
            };
            if rc == 0 {
                self.grabbed[i] = on;
                n += 1;
            } else {
                crate::logw!(
                    "power key {}: {}",
                    if on { "grab" } else { "release" },
                    crate::platform::errstr()
                );
            }
        }
        if n > 0 {
            crate::logi!(
                "power key {} ({}{})",
                if on { "blocked" } else { "released" },
                n,
                if n == 1 { " device" } else { " devices" }
            );
        }
        n
    }

    pub fn power_check(&mut self) {
        if self.grab_until_sec == 0 {
            return;
        }
        if crate::platform::now_sec() < self.grab_until_sec {
            return;
        }
        crate::logw!("power key block not confirmed for 15 min: released");
        self.power_block(false);
    }

    fn escape(&mut self) {
        self.power_block(false);
        crate::logi!("escape gesture: waking the screen and telling the app");
        screen(true);
        broadcast(ESCAPE_ACTION);
    }

    pub fn key_event(&mut self, fd: RawFd) {
        let mut events = [0u8; std::mem::size_of::<InputEvent>() * 16];
        loop {
            let n = unsafe { libc::read(fd, events.as_mut_ptr() as *mut c_void, events.len()) };
            if n <= 0 {
                break;
            }
            let count = n as usize / std::mem::size_of::<InputEvent>();
            for i in 0..count {
                let ev = unsafe {
                    std::ptr::read_unaligned(
                        events.as_ptr().add(i * std::mem::size_of::<InputEvent>())
                            as *const InputEvent,
                    )
                };
                if ev.type_ != EV_KEY || ev.value != 1 {
                    continue;
                }
                if ev.code != KEY_VOLUMEUP && ev.code != KEY_VOLUMEDOWN {
                    continue;
                }
                let now = crate::platform::now_ns(libc::CLOCK_MONOTONIC);
                if self.gesture.push(ev.code == KEY_VOLUMEUP, now) {
                    self.escape();
                }
            }
        }
    }

    // -------------------------------------------------------------- log capture

    fn log_pid() -> i32 {
        let pid = read_int(LOG_PIDF);
        if pid <= 1 {
            return 0;
        }
        let comm = std::fs::read_to_string(format!("/proc/{}/comm", pid)).unwrap_or_default();
        if comm.starts_with("logcat") {
            pid
        } else {
            0
        }
    }

    fn log_stop() {
        let pid = Self::log_pid();
        if pid != 0 {
            unsafe { libc::kill(pid, libc::SIGTERM) };
        }
    }

    fn log_spawn(&mut self, since: Option<&str>) {
        let mut argv: Vec<&str> = vec![
            "/system/bin/logcat",
            "-b",
            "main,system,crash",
            "-v",
            "time",
            "-f",
            LOG_FILE,
            "-r",
            "2048",
            "-n",
            "5",
        ];
        if let Some(since) = since {
            argv.push("-T");
            argv.push(since);
        }
        for tag in [
            "-s",
            "HermesSimHost:I",
            "hermessimd:I",
            "voice:D",
            "Telecom:I",
            "SemWifiApTimeOutImpl:I",
            "Tethering:W",
            "AndroidRuntime:E",
        ] {
            argv.push(tag);
        }
        let pid = match crate::platform::spawn(&argv) {
            Ok(pid) => pid,
            Err(e) => {
                crate::logw!("spawn {}: {}", argv[0], crate::platform::err_text(&e));
                crate::logw!("log capture: spawn failed");
                return;
            }
        };
        write_file(LOG_PIDF, &format!("{}\n", pid));
        self.log_started_sec = crate::platform::now_sec();
        crate::logi!(
            "log capture -> {} (pid {}{}{})",
            LOG_FILE,
            pid,
            if since.is_some() { ", from " } else { "" },
            if since.is_some() {
                since.unwrap_or("")
            } else {
                ""
            }
        );
    }

    pub fn log_capture(&mut self) {
        mkdir_mode("/data/adb/hermessim", 0o700);
        mkdir_mode("/data/adb/hermessim/logs", 0o700);
        Self::log_stop();
        self.log_spawn(None);
    }

    pub fn log_watch(&mut self) {
        let now = crate::platform::now_sec();
        if self.log_last_sec != 0 && now - self.log_last_sec < 60 {
            return;
        }
        self.log_last_sec = now;
        if now - self.log_started_sec < 60 || Self::log_pid() != 0 {
            return;
        }
        let size = std::fs::metadata(LOG_FILE).map(|m| m.len()).unwrap_or(0);
        let mut tail = Vec::new();
        if let Ok(f) = std::fs::File::open(LOG_FILE) {
            use std::os::unix::fs::FileExt;
            let off = size.saturating_sub(2047);
            let mut buf = [0u8; 2047];
            if let Ok(n) = f.read_at(&mut buf, off) {
                tail.extend_from_slice(&buf[..n]);
            }
        }
        let mut since: Option<String> = None;
        let mut starts = Vec::new();
        let mut st = 0usize;
        for (i, &b) in tail.iter().enumerate() {
            if b == b'\n' {
                starts.push((st, i));
                st = i + 1;
            }
        }
        if st < tail.len() {
            starts.push((st, tail.len()));
        }
        for &(start, end) in starts.iter().rev() {
            if start == 0 {
                continue;
            }
            let line = &tail[start..end];
            if line.len() >= 18
                && line[2] == b'-'
                && line[5] == b' '
                && line[8] == b':'
                && line[14] == b'.'
            {
                since = Some(String::from_utf8_lossy(&line[..18]).into_owned());
                break;
            }
        }
        crate::logw!("log capture: logcat is gone, restarting it");
        self.log_spawn(since.as_deref());
    }

    pub fn announce(&self) {
        broadcast(DAEMON_UP);
    }
}

fn has_bit(bits: &[u64], bit: u16) -> bool {
    let i = bit as usize / 64;
    (bits[i] >> (bit as usize % 64)) & 1 != 0
}

fn broadcast(action: &str) {
    let argv = [
        "/system/bin/am",
        "broadcast",
        "--user",
        "0",
        "-a",
        action,
        "-n",
        ESCAPE_RECEIVER,
    ];
    spawn(&argv);
}
