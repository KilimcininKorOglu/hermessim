// hermessimd — HermesSIM host daemon, Rust reimplementation. GPL-3.0-or-later.
//
// The control protocol, mixer ownership, call routing, box mode and audio pump mirror the C daemon
// in host/daemon: same abstract sockets, same line protocol, same files, same log lines.

#[cfg(target_os = "android")]
#[macro_use]
mod platform;
#[cfg(target_os = "android")]
mod alsa;
#[cfg(target_os = "android")]
mod audio;
#[cfg(target_os = "android")]
mod boxmode;
#[cfg(target_os = "android")]
mod mixer;
#[cfg(target_os = "android")]
mod opus;
mod protocol;

#[cfg(not(target_os = "android"))]
fn main() {
    println!("hermessimd-rs targets Android; run cargo test for the pure helpers");
}

#[cfg(target_os = "android")]
fn main() {
    std::process::exit(run());
}

#[cfg(target_os = "android")]
mod daemon {
    use std::sync::atomic::{AtomicBool, Ordering};

    use libc::{c_int, c_void};

    use crate::boxmode::{self, BoxState, BOX_MAX_KEYDEVS};
    use crate::mixer::Mixer;
    use crate::protocol::{c_atoi, valid_iface, valid_uid};

    const VERSION: &str = "0.1";
    const SOCK_NAME: &[u8] = b"hermessimd";
    const INVENTORY_FILE: &str = "/data/adb/hermessim/inventory.txt";
    const EXIT_UPDATE: i32 = 10;

    static STOP: AtomicBool = AtomicBool::new(false);
    static KEEP_SILENCE: AtomicBool = AtomicBool::new(false);

    extern "C" fn on_signal(sig: c_int) {
        if sig == libc::SIGUSR1 {
            KEEP_SILENCE.store(true, Ordering::Relaxed);
        }
        STOP.store(true, Ordering::Relaxed);
    }

    extern "C" fn on_child(_sig: c_int) {}

    struct Conn {
        fd: libc::c_int,
        owner: bool,
        used: usize,
        out_used: usize,
        buf: [u8; 1024],
        out: [u8; 4096],
    }

    impl Conn {
        fn new(owner: bool) -> Self {
            Self {
                fd: -1,
                owner,
                used: 0,
                out_used: 0,
                buf: [0; 1024],
                out: [0; 4096],
            }
        }

        fn close(&mut self) {
            if self.fd >= 0 {
                unsafe { libc::close(self.fd) };
            }
            self.fd = -1;
            self.used = 0;
            self.out_used = 0;
        }

        fn flush(&mut self) {
            while self.fd >= 0 && self.out_used > 0 {
                let n = unsafe {
                    libc::send(
                        self.fd,
                        self.out.as_ptr() as *const c_void,
                        self.out_used,
                        libc::MSG_DONTWAIT | libc::MSG_NOSIGNAL,
                    )
                };
                if n > 0 {
                    let n = n as usize;
                    self.out.copy_within(n..self.out_used, 0);
                    self.out_used -= n;
                    continue;
                }
                if n < 0 {
                    let errno = std::io::Error::last_os_error().raw_os_error().unwrap_or(0);
                    if errno == libc::EAGAIN || errno == libc::EWOULDBLOCK || errno == libc::EINTR {
                        return;
                    }
                }
                self.out_used = 0;
                return;
            }
        }

        fn reply(&mut self, msg: String) {
            if self.fd < 0 {
                return;
            }
            let mut buf = msg.into_bytes();
            if buf.len() > 2046 {
                buf.truncate(2046);
            }
            buf.push(b'\n');
            if self.out_used + buf.len() > self.out.len() {
                crate::logw!("reply queue full on fd {}: dropping the client", self.fd);
                self.close();
                return;
            }
            self.out[self.out_used..self.out_used + buf.len()].copy_from_slice(&buf);
            self.out_used += buf.len();
            self.flush();
        }

        fn read(&mut self, mixer: &mut Mixer, boxm: &mut BoxState) -> bool {
            let n = unsafe {
                libc::recv(
                    self.fd,
                    self.buf.as_mut_ptr().add(self.used) as *mut c_void,
                    self.buf.len() - 1 - self.used,
                    libc::MSG_DONTWAIT,
                )
            };
            if n < 0 {
                let errno = std::io::Error::last_os_error().raw_os_error().unwrap_or(0);
                if errno == libc::EAGAIN || errno == libc::EWOULDBLOCK || errno == libc::EINTR {
                    return true;
                }
                return false;
            }
            if n <= 0 {
                return false;
            }
            self.used += n as usize;
            loop {
                let Some(pos) = self.buf[..self.used].iter().position(|&b| b == b'\n') else {
                    break;
                };
                let mut end = pos;
                if end > 0 && self.buf[end - 1] == b'\r' {
                    end -= 1;
                }
                let line = String::from_utf8_lossy(&self.buf[..end]).into_owned();
                self.buf.copy_within(pos + 1..self.used, 0);
                self.used -= pos + 1;
                handle_line(self, &line, mixer, boxm);
                if self.fd < 0 {
                    return false;
                }
            }
            if self.used == self.buf.len() - 1 {
                self.used = 0;
            }
            true
        }
    }

    struct Tokens<'a> {
        s: &'a str,
        pos: usize,
    }

    impl<'a> Tokens<'a> {
        fn new(s: &'a str) -> Self {
            Self { s, pos: 0 }
        }

        fn next(&mut self) -> Option<&'a str> {
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

        /// strtok(NULL, ""): the rest of the line from just after the token's delimiter.
        fn rest(&mut self) -> Option<&'a str> {
            if self.pos + 1 >= self.s.len() {
                return None;
            }
            let rest = &self.s[self.pos + 1..];
            self.pos = self.s.len();
            Some(rest)
        }
    }

    fn handle_line(c: &mut Conn, line: &str, mixer: &mut Mixer, boxm: &mut BoxState) {
        let mut t = Tokens::new(line);
        let Some(cmd) = t.next() else { return };
        match cmd {
            "HELLO" => c.reply(format!(
                "OK hermessimd {} card={} call={} session={}",
                VERSION, mixer.card, mixer.call as i32, mixer.session
            )),
            "STATUS" => {
                let names = mixer.status_names();
                c.reply(format!(
                    "OK call={} session={} managed={} silence={} safe={} {}",
                    mixer.call as i32,
                    mixer.session,
                    mixer.managed.len(),
                    mixer.perm.len(),
                    mixer.safe as i32,
                    names
                ));
            }
            "SET" => {
                let key = t.next();
                let val = t.rest();
                let ok = match (key, val) {
                    (Some(key), Some(val)) => mixer.set_key(key, val),
                    _ => false,
                };
                if ok {
                    c.reply("OK".into());
                } else {
                    c.reply("ERR bad SET".into());
                }
            }
            "CALL_START" => {
                let session = t.next().map(c_atoi).unwrap_or(1);
                if session != 1 && session != 2 {
                    c.reply("ERR session must be 1 or 2".into());
                    return;
                }
                let n = mixer.call_start(session);
                c.reply(format!("OK applied={}", n));
            }
            "IFSTATS" => {
                if let Some((rx, tx)) = ifstats(t.next()) {
                    c.reply(format!("OK rx={} tx={}", rx, tx));
                } else {
                    c.reply("ERR no such interface".into());
                }
            }
            "NEIGH" => {
                let mut list = String::new();
                let mut n = 0;
                if neigh(t.next(), &mut list, &mut n) {
                    c.reply(format!("OK {} {}", n, list));
                } else {
                    c.reply("ERR bad interface".into());
                }
            }
            "CALL_END" => {
                mixer.restore_all("CALL_END");
                c.reply("OK".into());
            }
            "INVENTORY" => {
                if mixer.call {
                    c.reply("ERR in call".into());
                } else {
                    match mixer.inventory(INVENTORY_FILE) {
                        Ok(n) => c.reply(format!("OK {} controls in {}", n, INVENTORY_FILE)),
                        Err(-1) => c.reply("ERR no mixer".into()),
                        Err(_) => c.reply("ERR write failed".into()),
                    }
                }
            }
            "HAS" => {
                let Some(name) = t.next() else {
                    c.reply("ERR no such control".into());
                    return;
                };
                match mixer.has(name) {
                    Some((count, items)) => {
                        let suffix = if items.is_empty() {
                            String::new()
                        } else {
                            format!(" items={}", items)
                        };
                        c.reply(format!("OK count={}{}", count, suffix));
                    }
                    None => c.reply("ERR no such control".into()),
                }
            }
            "AUDIO?" => {
                c.reply(format!(
                    "OK call={} session={} silence={} {}",
                    mixer.call as i32,
                    mixer.session,
                    mixer.perm.len(),
                    crate::audio::totals()
                ));
            }
            "STALL" => {
                crate::audio::stall(t.next().map(c_atoi).unwrap_or(0));
                c.reply("OK".into());
            }
            "TUNE" => {
                let prime = t.next();
                let keep = t.next();
                let period = t.next();
                if let (Some(p), Some(k), Some(per)) = (prime, keep, period) {
                    if !crate::audio::tune(c_atoi(p), c_atoi(k), c_atoi(per)) {
                        c.reply("ERR bad values".into());
                        return;
                    }
                }
                c.reply(format!("OK {}", crate::audio::tune_get()));
            }
            "POWERKEY" => {
                let on = t.next().map(|v| c_atoi(v) == 1).unwrap_or(false);
                let n = boxm.power_block(on);
                if n < 0 {
                    c.reply("ERR no escape gesture on this phone".into());
                } else {
                    c.reply(format!("OK {}", n));
                }
            }
            "SCREEN_OFF" | "WAKE" => {
                if boxmode::screen(cmd == "WAKE") == 0 {
                    c.reply("OK".into());
                } else {
                    c.reply("ERR spawn failed".into());
                }
            }
            "ROLE" => {
                let role = t.next();
                let pkg = t.next();
                let rc = match (role, pkg) {
                    (Some(role), Some(pkg)) => boxmode::role(role, pkg),
                    _ => -1,
                };
                if rc == 0 {
                    c.reply("OK".into());
                } else {
                    c.reply("ERR bad role or package".into());
                }
            }
            "NETBLOCK" => {
                let uid = t.next();
                let on = t.next();
                let ok = match (uid, on) {
                    (Some(uid), Some(on)) => boxm.netblock(c_atoi(uid), c_atoi(on) != 0) == 0,
                    _ => false,
                };
                if ok {
                    c.reply("OK".into());
                } else {
                    c.reply("ERR bad uid".into());
                }
            }
            "CHARGE?" => {
                if let Some(node) = boxm.charge_node() {
                    c.reply(format!("OK {} {}", node, boxm.charge_status()));
                } else {
                    c.reply("ERR unsupported".into());
                }
            }
            "CHARGE" => {
                let rc = boxm.charge(t.next().map(c_atoi).unwrap_or(-1));
                if rc == 0 {
                    c.reply("OK".into());
                } else if rc == -2 {
                    c.reply("ERR unsupported".into());
                } else {
                    c.reply("ERR failed".into());
                }
            }
            _ => c.reply("ERR unknown command".into()),
        }
    }

    fn ifstats(iface: Option<&str>) -> Option<(i64, i64)> {
        let iface = iface?;
        if !valid_iface(iface) {
            return None;
        }
        let read = |name: &str| -> Option<i64> {
            let path = format!("/sys/class/net/{}/statistics/{}", iface, name);
            let text = std::fs::read_to_string(path).ok()?;
            text.trim().parse::<i64>().ok()
        };
        Some((read("rx_bytes")?, read("tx_bytes")?))
    }

    fn neigh(iface: Option<&str>, out: &mut String, count: &mut i32) -> bool {
        let Some(iface) = iface else { return false };
        if !valid_iface(iface) {
            return false;
        }
        let Ok(text) = std::fs::read_to_string("/proc/net/arp") else {
            return false;
        };
        let mut lines = text.lines();
        if lines.next().is_none() {
            return false;
        }
        *count = 0;
        for line in lines {
            let fields: Vec<&str> = line.split_whitespace().collect();
            if fields.len() < 6 {
                continue;
            }
            let ip = fields[0];
            let hw = fields[3];
            let dev = fields[5];
            let flags = u32::from_str_radix(fields[2].trim_start_matches("0x"), 16).unwrap_or(0);
            if dev != iface || flags & 0x2 == 0 {
                continue;
            }
            let entry = format!("{},{}", hw, ip);
            let sep = if out.is_empty() { 0 } else { 1 };
            if out.len() + sep + entry.len() >= 1400 {
                break;
            }
            if sep == 1 {
                out.push(';');
            }
            out.push_str(&entry);
            *count += 1;
        }
        true
    }

    fn peer_uid(fd: libc::c_int, allowed: u32) -> Option<u32> {
        let mut cr: libc::ucred = unsafe { std::mem::zeroed() };
        let mut len = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
        let rc = unsafe {
            libc::getsockopt(
                fd,
                libc::SOL_SOCKET,
                libc::SO_PEERCRED,
                &mut cr as *mut libc::ucred as *mut c_void,
                &mut len,
            )
        };
        if rc < 0 {
            return None;
        }
        let ok = cr.uid == 0 || (allowed != u32::MAX && cr.uid == allowed);
        crate::logi!(
            "client pid={} uid={} {}",
            cr.pid,
            cr.uid,
            if ok { "accepted" } else { "REJECTED" }
        );
        if ok {
            Some(cr.uid)
        } else {
            None
        }
    }

    fn run_client(script: &str) -> i32 {
        let mut cut = script.len().min(1023);
        while !script.is_char_boundary(cut) {
            cut -= 1;
        }
        let script = &script[..cut];
        let fd = match crate::platform::connect_abstract(SOCK_NAME) {
            Ok(fd) => fd,
            Err(e) => {
                eprintln!("connect @hermessimd: {}", e);
                return 1;
            }
        };
        let mut rc = 0;
        for cmd in script.split('|').filter(|s| !s.is_empty()) {
            if let Some(ms) = cmd.strip_prefix("SLEEP ") {
                crate::platform::sleep_ns(c_atoi(ms) as i64 * 1_000_000_000);
                continue;
            }
            let line = format!("{}\n", cmd);
            let sent = unsafe {
                libc::send(
                    fd,
                    line.as_ptr() as *const c_void,
                    line.len(),
                    libc::MSG_NOSIGNAL,
                )
            };
            if sent < 0 {
                rc = 1;
                break;
            }
            let mut buf = [0u8; 512];
            let n = unsafe { libc::read(fd, buf.as_mut_ptr() as *mut c_void, buf.len() - 1) };
            if n <= 0 {
                rc = 1;
                break;
            }
            let text = String::from_utf8_lossy(&buf[..n as usize]);
            print!("{} -> {}", cmd, text);
            if !text.starts_with("OK") {
                rc = 1;
            }
        }
        unsafe { libc::close(fd) };
        rc
    }

    fn parse_uid(s: &str) -> Option<u32> {
        valid_uid(s)
    }

    pub fn run() -> i32 {
        let mut allowed: u32 = u32::MAX;
        let mut card: i32 = 0;
        let mut card_given = false;
        let mut loopback = false;
        let mut safe = false;

        let args: Vec<String> = std::env::args().collect();
        let mut i = 1;
        while i < args.len() {
            match args[i].as_str() {
                "--client" if i + 1 < args.len() => {
                    return run_client(&args[i + 1]);
                }
                "--uid" if i + 1 < args.len() => {
                    i += 1;
                    match parse_uid(&args[i]) {
                        Some(uid) => allowed = uid,
                        None => {
                            crate::loge!("--uid '{}' is not a uid: root only", args[i]);
                            allowed = u32::MAX;
                        }
                    }
                }
                "--card" if i + 1 < args.len() => {
                    i += 1;
                    card = c_atoi(&args[i]);
                    card_given = true;
                }
                "--loopback" => loopback = true,
                "--safe" => {
                    safe = true;
                    crate::loge!("SAFE MODE: started after repeated failures; no mixer writes");
                }
                _ => {}
            }
            i += 1;
        }

        let mut ctl = -1;
        let first = if card_given { card } else { 0 };
        let last = if card_given { card + 1 } else { 8 };
        let mut c = first;
        while c < last {
            let path = format!("/dev/snd/controlC{}", c);
            if let Some(fd) = crate::alsa::open_ctl(&path) {
                ctl = fd;
                card = c;
                break;
            }
            c += 1;
        }
        if ctl < 0 {
            crate::loge!(
                "no sound card mixer ({}): running without call audio",
                std::io::Error::last_os_error()
            );
        } else if !crate::alsa::subscribe_events(ctl) {
            crate::logw!(
                "event subscribe failed: {}",
                std::io::Error::last_os_error()
            );
        }

        let ls = match crate::platform::bind_abstract(SOCK_NAME, libc::SOCK_STREAM, 2) {
            Ok(fd) => fd,
            Err(e) => {
                crate::loge!("socket @{}: {}", String::from_utf8_lossy(SOCK_NAME), e);
                return 1;
            }
        };

        let waitmask = unsafe { install_signals() };

        let mut mixer = Mixer::new(card, ctl);
        mixer.safe = safe;
        mixer.profile.loopback = loopback;
        let mut boxm = BoxState::new();
        boxm.charge_init();
        mixer.crumb.start();
        mixer.clear_device_mutes();
        if !mixer.safe {
            mixer.call_orig_restore();
        }
        crate::audio::init(if allowed == u32::MAX {
            -1
        } else {
            allowed as i32
        });
        boxm.keys_open();
        let uev = BoxState::uevent_open();
        boxm.announce();
        boxm.log_capture();
        crate::logi!(
            "hermessimd {} up: card {}, socket @{}, allowed uid {}",
            VERSION,
            card,
            String::from_utf8_lossy(SOCK_NAME),
            if allowed == u32::MAX {
                -1
            } else {
                allowed as i32
            }
        );

        let mut conns = [Conn::new(true), Conn::new(false)];
        let mut heartbeat = crate::platform::now_sec() + 60;
        let mut exit_code = 0i32;

        while !STOP.load(Ordering::Relaxed) {
            let mut fds = [libc::pollfd {
                fd: -1,
                events: 0,
                revents: 0,
            }; 5 + BOX_MAX_KEYDEVS];
            fds[0] = libc::pollfd {
                fd: ls,
                events: libc::POLLIN,
                revents: 0,
            };
            fds[1] = libc::pollfd {
                fd: mixer.ctl,
                events: libc::POLLIN,
                revents: 0,
            };
            fds[2] = libc::pollfd {
                fd: conns[0].fd,
                events: libc::POLLIN
                    | if conns[0].out_used > 0 {
                        libc::POLLOUT
                    } else {
                        0
                    },
                revents: 0,
            };
            fds[3] = libc::pollfd {
                fd: uev,
                events: libc::POLLIN,
                revents: 0,
            };
            fds[4] = libc::pollfd {
                fd: conns[1].fd,
                events: libc::POLLIN
                    | if conns[1].out_used > 0 {
                        libc::POLLOUT
                    } else {
                        0
                    },
                revents: 0,
            };
            let nk = boxm.key_count();
            for k in 0..nk as usize {
                fds[5 + k] = libc::pollfd {
                    fd: boxm.key_fd(k),
                    events: libc::POLLIN,
                    revents: 0,
                };
            }

            boxm.reap();
            boxm.log_watch();
            boxm.power_check();
            let now = crate::platform::now_sec();
            if now >= heartbeat {
                boxm.charge_check();
                heartbeat = now + 60;
            }
            let mut wait_ms = (heartbeat - now) * 1000;
            let crumb = mixer.crumb.tick();
            if crumb >= 0 && crumb + 50 < wait_ms {
                wait_ms = crumb + 50;
            }
            let to = libc::timespec {
                tv_sec: (wait_ms / 1000) as libc::time_t,
                tv_nsec: ((wait_ms % 1000) * 1_000_000) as libc::c_long,
            };
            let pr = unsafe {
                libc::ppoll(
                    fds.as_mut_ptr(),
                    (5 + nk as usize) as libc::nfds_t,
                    &to,
                    &waitmask,
                )
            };
            if pr < 0 {
                if std::io::Error::last_os_error().raw_os_error() == Some(libc::EINTR) {
                    continue;
                }
                crate::loge!("poll: {}", std::io::Error::last_os_error());
                break;
            }
            if pr == 0 {
                continue;
            }

            if fds[1].revents & (libc::POLLERR | libc::POLLHUP | libc::POLLNVAL) != 0 {
                crate::loge!("sound card gone: restarting");
                unsafe { libc::close(mixer.ctl) };
                mixer.ctl = -1;
                exit_code = 3;
                break;
            }
            for k in 0..nk as usize {
                if fds[5 + k].revents & (libc::POLLERR | libc::POLLHUP | libc::POLLNVAL) != 0 {
                    boxm.key_drop(k);
                }
            }

            if fds[1].revents & libc::POLLIN != 0 {
                mixer.drain_ctl_events();
            }
            if fds[3].revents & libc::POLLIN != 0 {
                boxm.uevent(uev);
            }
            for k in 0..nk as usize {
                if fds[5 + k].revents & libc::POLLIN == 0 {
                    continue;
                }
                let kfd = boxm.key_fd(k);
                if kfd < 0 || kfd != fds[5 + k].fd {
                    continue;
                }
                boxm.key_event(kfd);
            }

            if fds[0].revents & libc::POLLIN != 0 {
                let c = unsafe {
                    libc::accept4(
                        ls,
                        std::ptr::null_mut(),
                        std::ptr::null_mut(),
                        libc::SOCK_CLOEXEC | libc::SOCK_NONBLOCK,
                    )
                };
                let uid = if c >= 0 { peer_uid(c, allowed) } else { None };
                if c >= 0 {
                    match uid {
                        None => unsafe {
                            libc::close(c);
                        },
                        Some(uid) => {
                            let owner = allowed == u32::MAX || uid == allowed;
                            let slot = if owner { 0 } else { 1 };
                            if conns[slot].fd >= 0 {
                                conns[slot].reply("ERR replaced by a new client".into());
                                conns[slot].close();
                            }
                            conns[slot].fd = c;
                        }
                    }
                }
            }

            for k in 0..2usize {
                let pf = &fds[if k == 0 { 2 } else { 4 }];
                let c = &mut conns[k];
                if c.fd < 0 || pf.fd != c.fd {
                    continue;
                }
                if pf.revents & libc::POLLOUT != 0 {
                    c.flush();
                }
                if pf.revents & (libc::POLLIN | libc::POLLHUP | libc::POLLERR) == 0 {
                    continue;
                }
                if c.read(&mut mixer, &mut boxm) {
                    continue;
                }
                crate::logi!("{} gone", if c.owner { "client" } else { "tool client" });
                if c.owner && mixer.call {
                    mixer.restore_all("client disconnected mid-call");
                }
                c.close();
            }
        }

        mixer.restore_all("exit");
        if KEEP_SILENCE.load(Ordering::Relaxed) {
            crate::logi!(
                "restart for an update: silence controls ({}) and charge limit left held",
                mixer.perm.len()
            );
        } else {
            boxm.charge_shutdown();
            mixer.restore_perm();
        }
        crate::audio::shutdown();
        mixer.crumb.clear();
        if KEEP_SILENCE.load(Ordering::Relaxed) && exit_code == 0 {
            exit_code = EXIT_UPDATE;
        }
        crate::logi!("hermessimd stopped");
        exit_code
    }

    unsafe fn install_signals() -> libc::sigset_t {
        let mut block: libc::sigset_t = std::mem::zeroed();
        libc::sigemptyset(&mut block);
        for sig in [libc::SIGTERM, libc::SIGINT, libc::SIGUSR1, libc::SIGCHLD] {
            libc::sigaddset(&mut block, sig);
        }
        let mut wait: libc::sigset_t = std::mem::zeroed();
        libc::sigprocmask(libc::SIG_BLOCK, &block, &mut wait);
        for sig in [libc::SIGTERM, libc::SIGINT, libc::SIGUSR1, libc::SIGCHLD] {
            libc::sigdelset(&mut wait, sig);
        }
        let mut sa: libc::sigaction = std::mem::zeroed();
        sa.sa_sigaction = on_signal as *const () as libc::sighandler_t;
        libc::sigemptyset(&mut sa.sa_mask);
        libc::sigaction(libc::SIGTERM, &sa, std::ptr::null_mut());
        libc::sigaction(libc::SIGINT, &sa, std::ptr::null_mut());
        libc::sigaction(libc::SIGUSR1, &sa, std::ptr::null_mut());
        let mut sc: libc::sigaction = std::mem::zeroed();
        sc.sa_sigaction = on_child as *const () as libc::sighandler_t;
        libc::sigemptyset(&mut sc.sa_mask);
        libc::sigaction(libc::SIGCHLD, &sc, std::ptr::null_mut());
        libc::signal(libc::SIGPIPE, libc::SIG_IGN);
        wait
    }
}

#[cfg(target_os = "android")]
fn run() -> i32 {
    daemon::run()
}
