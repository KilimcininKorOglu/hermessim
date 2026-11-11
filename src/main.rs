// hermessimd — HermesSIM host daemon, Rust reimplementation. GPL-3.0-or-later.
//
// The control protocol, mixer ownership, call routing, box mode and audio pump mirror the C daemon
// in host/daemon: same abstract sockets, same line protocol, same files, same log lines.
//
// Protocol: newline-terminated text lines over the abstract unix socket "@hermessimd"; the client
// must be root or the uid given with --uid (checked with SO_PEERCRED).
//   HELLO | STATUS | SET <key> <value> | CALL_START <session> | CALL_END   ->   OK ... / ERR ...
//   IFSTATS <iface>  -> OK rx=<bytes> tx=<bytes>   (hotspot data counter; sysfs needs root)
//   NEIGH <iface>    -> OK <n> <mac>,<ip>;...      (hotspot devices from the kernel ARP table)
//   SET keys: capture, rec_config, inject.1, inject.2, mute.tx, mute.rx, mute.vsid, mute.ramp
//   HAS <control>  -> OK count=n [items=a|b|c] | ERR   (auto profiles: a control on this card, an enum's options)
//   INVENTORY      -> OK <n> controls in <file>
//   TUNE [p k per] -> OK prime= keep= period=   (jitter-buffer frames, inject PCM period; live)
//   AUDIO?         -> OK call= session= silence= cap= voiced= sent= played= peak= txdrop= noclient= recv= plc=
//                     starved= live= capms= caperr=   (this call's totals: the call test, the app's end-of-call check)
//   Box mode (boxmode.rs): SCREEN_OFF | WAKE | POWERKEY 1|0 | ROLE <role> <pkg> | CHARGE <target> |
//                     CHARGE? -> OK <node> target= resume= drain= cutting= held=0|1 stuck=0|1 | ERR
//                     NETBLOCK <uid> <0|1> (iptables chain hermessim_block)
//   NETCYCLE <seconds> -> OK | ERR busy | ERR failed   (airplane mode on for 3-30 s, then off, from a detached script)
//                     + the Volume Up x3 / Volume Down x3 escape gesture, reported to the app by broadcast
//   STALL <ms>     -> OK   (debug: stall the playback thread once)
// Debug client: hermessimd --client "CMD|SLEEP n|CMD..." (one connection)

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
    use crate::protocol::{c_atoi, valid_iface, valid_uid, Tokens};

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

        /// Read what the client sent and handle every whole line. False when the client is gone.
        fn read(&mut self, mixer: &mut Mixer, boxm: &mut BoxState) -> bool {
            if let Some(keep) = self.recv_more() {
                return keep;
            }
            while let Some(line) = self.take_line() {
                handle_line(self, &line, mixer, boxm);
                if self.fd < 0 {
                    return false;
                }
            }
            if self.used == self.buf.len() - 1 {
                self.used = 0; // an overlong line is dropped
            }
            true
        }

        /// Append received bytes to the line buffer. None when bytes came in; Some(keep) when
        /// nothing did: true for a would-block or interrupted read, false for an error or EOF.
        fn recv_more(&mut self) -> Option<bool> {
            let n = unsafe {
                libc::recv(
                    self.fd,
                    self.buf.as_mut_ptr().add(self.used) as *mut c_void,
                    self.buf.len() - 1 - self.used,
                    libc::MSG_DONTWAIT,
                )
            };
            if n > 0 {
                self.used += n as usize;
                return None;
            }
            if n == 0 {
                return Some(false);
            }
            let errno = std::io::Error::last_os_error().raw_os_error().unwrap_or(0);
            Some(errno == libc::EAGAIN || errno == libc::EWOULDBLOCK || errno == libc::EINTR)
        }

        /// The next whole line without its "\n" or "\r\n", removed from the buffer.
        fn take_line(&mut self) -> Option<String> {
            let pos = self.buf[..self.used].iter().position(|&b| b == b'\n')?;
            let end = if pos > 0 && self.buf[pos - 1] == b'\r' {
                pos - 1
            } else {
                pos
            };
            let line = String::from_utf8_lossy(&self.buf[..end]).into_owned();
            self.buf.copy_within(pos + 1..self.used, 0);
            self.used -= pos + 1;
            Some(line)
        }
    }

    /// One protocol line: every command gets exactly one reply line.
    fn handle_line(c: &mut Conn, line: &str, mixer: &mut Mixer, boxm: &mut BoxState) {
        let mut t = Tokens::new(line);
        let Some(cmd) = t.next() else { return };
        let reply = mixer_command(cmd, &mut t, mixer)
            .or_else(|| box_command(cmd, &mut t, boxm))
            .or_else(|| other_command(cmd, &mut t))
            .unwrap_or_else(|| "ERR unknown command".into());
        c.reply(reply);
    }

    /// The mixer and call commands; None for any other command.
    fn mixer_command(cmd: &str, t: &mut Tokens, mixer: &mut Mixer) -> Option<String> {
        Some(match cmd {
            "HELLO" => format!(
                "OK hermessimd {} card={} call={} session={}",
                VERSION, mixer.card, mixer.call as i32, mixer.session
            ),
            "STATUS" => cmd_status(mixer),
            "SET" => cmd_set(t, mixer),
            "CALL_START" => cmd_call_start(t, mixer),
            "CALL_END" => {
                mixer.restore_all("CALL_END");
                "OK".into()
            }
            "INVENTORY" => cmd_inventory(mixer),
            "HAS" => cmd_has(t, mixer),
            "AUDIO?" => format!(
                "OK call={} session={} silence={} {}",
                mixer.call as i32,
                mixer.session,
                mixer.perm.len(),
                crate::audio::totals()
            ),
            _ => return None,
        })
    }

    /// The box mode commands; None for any other command.
    fn box_command(cmd: &str, t: &mut Tokens, boxm: &mut BoxState) -> Option<String> {
        Some(match cmd {
            "POWERKEY" => cmd_powerkey(t, boxm),
            "SCREEN_OFF" => ok_or(boxmode::screen(false) == 0, "ERR spawn failed"),
            "WAKE" => ok_or(boxmode::screen(true) == 0, "ERR spawn failed"),
            "ROLE" => cmd_role(t),
            "NETBLOCK" => cmd_netblock(t, boxm),
            "NETCYCLE" => cmd_netcycle(t, boxm),
            "CHARGE?" => cmd_charge_query(boxm),
            "CHARGE" => cmd_charge(t, boxm),
            _ => return None,
        })
    }

    /// The hotspot and audio tuning commands; None for any other command.
    fn other_command(cmd: &str, t: &mut Tokens) -> Option<String> {
        Some(match cmd {
            "IFSTATS" => cmd_ifstats(t),
            "NEIGH" => cmd_neigh(t),
            "STALL" => {
                crate::audio::stall(t.next().map(c_atoi).unwrap_or(0));
                "OK".into()
            }
            "TUNE" => cmd_tune(t),
            _ => return None,
        })
    }

    fn ok_or(ok: bool, err: &str) -> String {
        if ok { "OK" } else { err }.into()
    }

    fn cmd_status(mixer: &Mixer) -> String {
        format!(
            "OK call={} session={} managed={} silence={} safe={} {}",
            mixer.call as i32,
            mixer.session,
            mixer.managed.len(),
            mixer.perm.len(),
            mixer.safe as i32,
            mixer.status_names()
        )
    }

    fn cmd_set(t: &mut Tokens, mixer: &mut Mixer) -> String {
        let key = t.next();
        let val = t.rest();
        let ok = match (key, val) {
            (Some(key), Some(val)) => mixer.set_key(key, val),
            _ => false,
        };
        ok_or(ok, "ERR bad SET")
    }

    fn cmd_call_start(t: &mut Tokens, mixer: &mut Mixer) -> String {
        let session = t.next().map(c_atoi).unwrap_or(1);
        if session != 1 && session != 2 {
            return "ERR session must be 1 or 2".into();
        }
        format!("OK applied={}", mixer.call_start(session))
    }

    fn cmd_ifstats(t: &mut Tokens) -> String {
        match ifstats(t.next()) {
            Some((rx, tx)) => format!("OK rx={} tx={}", rx, tx),
            None => "ERR no such interface".into(),
        }
    }

    fn cmd_neigh(t: &mut Tokens) -> String {
        let mut list = String::new();
        let mut n = 0;
        if neigh(t.next(), &mut list, &mut n) {
            format!("OK {} {}", n, list)
        } else {
            "ERR bad interface".into()
        }
    }

    fn cmd_inventory(mixer: &mut Mixer) -> String {
        if mixer.call {
            return "ERR in call".into();
        }
        match mixer.inventory(INVENTORY_FILE) {
            Ok(n) => format!("OK {} controls in {}", n, INVENTORY_FILE),
            Err(-1) => "ERR no mixer".into(),
            Err(_) => "ERR write failed".into(),
        }
    }

    fn cmd_has(t: &mut Tokens, mixer: &mut Mixer) -> String {
        match t.rest().and_then(|name| mixer.has(name)) {
            Some((count, items)) if items.is_empty() => format!("OK count={}", count),
            Some((count, items)) => format!("OK count={} items={}", count, items),
            None => "ERR no such control".into(),
        }
    }

    fn cmd_tune(t: &mut Tokens) -> String {
        let (prime, keep, period) = (t.next(), t.next(), t.next());
        if let (Some(p), Some(k), Some(per)) = (prime, keep, period) {
            if !crate::audio::tune(c_atoi(p), c_atoi(k), c_atoi(per)) {
                return "ERR bad values".into();
            }
        }
        format!("OK {}", crate::audio::tune_get())
    }

    fn cmd_powerkey(t: &mut Tokens, boxm: &mut BoxState) -> String {
        let on = t.next().map(|v| c_atoi(v) == 1).unwrap_or(false);
        let n = boxm.power_block(on);
        if n < 0 {
            "ERR no escape gesture on this phone".into()
        } else {
            format!("OK {}", n)
        }
    }

    fn cmd_role(t: &mut Tokens) -> String {
        let rc = match (t.next(), t.next()) {
            (Some(role), Some(pkg)) => boxmode::role(role, pkg),
            _ => -1,
        };
        ok_or(rc == 0, "ERR bad role or package")
    }

    fn cmd_netblock(t: &mut Tokens, boxm: &mut BoxState) -> String {
        let ok = match (t.next(), t.next()) {
            (Some(uid), Some(on)) => boxm.netblock(c_atoi(uid), c_atoi(on) != 0) == 0,
            _ => false,
        };
        ok_or(ok, "ERR bad uid")
    }

    fn cmd_netcycle(t: &mut Tokens, boxm: &mut BoxState) -> String {
        match boxm.net_cycle(t.next().map(c_atoi).unwrap_or(0)) {
            0 => "OK",
            -2 => "ERR busy",
            _ => "ERR failed",
        }
        .into()
    }

    fn cmd_charge_query(boxm: &mut BoxState) -> String {
        match boxm.charge_node() {
            Some(node) => format!("OK {} {}", node, boxm.charge_status()),
            None => "ERR unsupported".into(),
        }
    }

    fn cmd_charge(t: &mut Tokens, boxm: &mut BoxState) -> String {
        match boxm.charge(t.next().map(c_atoi).unwrap_or(-1)) {
            0 => "OK",
            -2 => "ERR unsupported",
            _ => "ERR failed",
        }
        .into()
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
            let Some((ip, hw, dev, flags)) = crate::protocol::parse_arp_line(line) else {
                continue;
            };
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
                eprintln!("connect @hermessimd: {}", crate::platform::err_text(&e)); // perror's wording
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

    struct Args {
        allowed: u32,
        card: Option<i32>,
        loopback: bool,
        safe: bool,
    }

    /// The command line; Err holds the exit code of a `--client` run.
    fn parse_args() -> Result<Args, i32> {
        let mut a = Args {
            allowed: u32::MAX,
            card: None,
            loopback: false,
            safe: false,
        };
        let args: Vec<String> = std::env::args().collect();
        let mut i = 1;
        while i < args.len() {
            let next = args.get(i + 1);
            match (args[i].as_str(), next) {
                ("--client", Some(script)) => return Err(run_client(script)),
                ("--uid", Some(v)) => {
                    i += 1;
                    a.allowed = parse_uid(v).unwrap_or_else(|| {
                        crate::loge!("--uid '{}' is not a uid: root only", v);
                        u32::MAX
                    });
                }
                ("--card", Some(v)) => {
                    i += 1;
                    a.card = Some(c_atoi(v));
                }
                ("--loopback", _) => a.loopback = true,
                ("--safe", _) => {
                    a.safe = true;
                    crate::loge!("SAFE MODE: started after repeated failures; no mixer writes");
                }
                _ => {}
            }
            i += 1;
        }
        Ok(a)
    }

    /// The sound card's control node: the given card, else the first of cards 0-7 that opens.
    /// Returns the fd (-1 when none opened) and the card number.
    fn open_card(given: Option<i32>) -> (libc::c_int, i32) {
        let cards = match given {
            Some(c) => c..c + 1,
            None => 0..8,
        };
        let mut found = (-1, given.unwrap_or(0));
        for c in cards {
            if let Some(fd) = crate::alsa::open_ctl(&format!("/dev/snd/controlC{}", c)) {
                found = (fd, c);
                break;
            }
        }
        if found.0 < 0 {
            crate::loge!(
                "no sound card mixer ({}): running without call audio",
                crate::platform::errstr()
            );
        } else if !crate::alsa::subscribe_events(found.0) {
            crate::logw!("event subscribe failed: {}", crate::platform::errstr());
        }
        found
    }

    fn uid_text(allowed: u32) -> i32 {
        if allowed == u32::MAX {
            -1
        } else {
            allowed as i32
        }
    }

    fn pollfd(fd: libc::c_int, events: libc::c_short) -> libc::pollfd {
        libc::pollfd {
            fd,
            events,
            revents: 0,
        }
    }

    const GONE: libc::c_short = libc::POLLERR | libc::POLLHUP | libc::POLLNVAL;
    type PollSet = [libc::pollfd; 5 + BOX_MAX_KEYDEVS];

    /// The daemon's main-loop state.
    struct Daemon {
        ls: libc::c_int,
        uev: libc::c_int,
        allowed: u32,
        mixer: Mixer,
        boxm: BoxState,
        conns: [Conn; 2],
        heartbeat: i64,
    }

    impl Daemon {
        /// Listener, mixer, conns and key devices, in the main loop's fixed slots.
        fn poll_set(&self) -> (PollSet, usize) {
            let mut fds = [pollfd(-1, 0); 5 + BOX_MAX_KEYDEVS];
            let conn_events =
                |c: &Conn| libc::POLLIN | if c.out_used > 0 { libc::POLLOUT } else { 0 };
            fds[0] = pollfd(self.ls, libc::POLLIN);
            fds[1] = pollfd(self.mixer.ctl, libc::POLLIN);
            fds[2] = pollfd(self.conns[0].fd, conn_events(&self.conns[0]));
            fds[3] = pollfd(self.uev, libc::POLLIN);
            fds[4] = pollfd(self.conns[1].fd, conn_events(&self.conns[1]));
            let nk = self.boxm.key_count() as usize;
            for (k, slot) in fds[5..5 + nk].iter_mut().enumerate() {
                *slot = pollfd(self.boxm.key_fd(k), libc::POLLIN);
            }
            (fds, nk)
        }

        /// Timed work before each poll; returns the poll timeout in ms (the next heartbeat, or
        /// the crumb's deadline when that comes first).
        fn housekeeping(&mut self) -> i64 {
            self.boxm.reap();
            self.boxm.log_watch();
            self.boxm.power_check();
            let now = crate::platform::now_sec();
            if now >= self.heartbeat {
                self.boxm.charge_check();
                self.heartbeat = now + 60;
            }
            let wait_ms = (self.heartbeat - now) * 1000;
            let crumb = self.mixer.crumb.tick();
            if crumb >= 0 && crumb + 50 < wait_ms {
                crumb + 50
            } else {
                wait_ms
            }
        }

        /// Handle one poll's events; Some(exit code) ends the loop.
        fn dispatch(&mut self, fds: &PollSet, nk: usize) -> Option<i32> {
            if fds[1].revents & GONE != 0 {
                crate::loge!("sound card gone: restarting");
                unsafe { libc::close(self.mixer.ctl) };
                self.mixer.ctl = -1;
                return Some(3);
            }
            for k in 0..nk {
                if fds[5 + k].revents & GONE != 0 {
                    self.boxm.key_drop(k);
                }
            }
            if fds[1].revents & libc::POLLIN != 0 {
                self.mixer.drain_ctl_events();
            }
            if fds[3].revents & libc::POLLIN != 0 {
                self.boxm.uevent(self.uev);
            }
            self.key_events(fds, nk);
            if fds[0].revents & libc::POLLIN != 0 {
                self.accept();
            }
            self.serve(0, &fds[2]);
            self.serve(1, &fds[4]);
            None
        }

        fn key_events(&mut self, fds: &PollSet, nk: usize) {
            for k in 0..nk {
                let kfd = self.boxm.key_fd(k);
                if fds[5 + k].revents & libc::POLLIN != 0 && kfd >= 0 && kfd == fds[5 + k].fd {
                    self.boxm.key_event(kfd);
                }
            }
        }

        /// A new client takes the owner slot (the app's uid, or anyone when no uid was given)
        /// or the tool slot, replacing the one there.
        fn accept(&mut self) {
            let c = unsafe {
                libc::accept4(
                    self.ls,
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                    libc::SOCK_CLOEXEC | libc::SOCK_NONBLOCK,
                )
            };
            if c < 0 {
                return;
            }
            let Some(uid) = peer_uid(c, self.allowed) else {
                unsafe { libc::close(c) };
                return;
            };
            let owner = self.allowed == u32::MAX || uid == self.allowed;
            let conn = &mut self.conns[if owner { 0 } else { 1 }];
            if conn.fd >= 0 {
                conn.reply("ERR replaced by a new client".into());
                conn.close();
            }
            conn.fd = c;
        }

        /// Flush and read connection `k`; a gone owner mid-call restores the call's controls.
        fn serve(&mut self, k: usize, pf: &libc::pollfd) {
            let c = &mut self.conns[k];
            if c.fd < 0 || pf.fd != c.fd {
                return;
            }
            if pf.revents & libc::POLLOUT != 0 {
                c.flush();
            }
            if pf.revents & (libc::POLLIN | libc::POLLHUP | libc::POLLERR) == 0 {
                return;
            }
            if c.read(&mut self.mixer, &mut self.boxm) {
                return;
            }
            crate::logi!("{} gone", if c.owner { "client" } else { "tool client" });
            if c.owner && self.mixer.call {
                self.mixer.restore_all("client disconnected mid-call");
            }
            c.close();
        }

        /// Restore every held control and stop; SIGUSR1 (an update restart) keeps the silence
        /// controls and the charge limit held and exits with EXIT_UPDATE.
        fn shutdown(mut self, exit_code: i32) -> i32 {
            self.mixer.restore_all("exit");
            let keep = KEEP_SILENCE.load(Ordering::Relaxed);
            if keep {
                crate::logi!(
                    "restart for an update: silence controls ({}) and charge limit left held",
                    self.mixer.perm.len()
                );
            } else {
                self.boxm.charge_shutdown();
                self.mixer.restore_perm();
            }
            crate::audio::shutdown();
            self.mixer.crumb.clear();
            crate::logi!("hermessimd stopped");
            if keep && exit_code == 0 {
                EXIT_UPDATE
            } else {
                exit_code
            }
        }
    }

    /// Mixer and box state from the parsed arguments, with the crash check and the call
    /// originals restore done before any client connects.
    fn start(a: &Args, ctl: libc::c_int, card: i32) -> (Mixer, BoxState) {
        let mut mixer = Mixer::new(card, ctl);
        mixer.profile.loopback = a.loopback;
        let mut boxm = BoxState::new();
        boxm.charge_init();
        // One safe flag, as in the C daemon: --safe and a crash during a mixer write both stop every write.
        mixer.crumb.safe = a.safe;
        mixer.crumb.start();
        mixer.safe = mixer.crumb.safe;
        mixer.clear_device_mutes();
        if !mixer.safe {
            mixer.call_orig_restore();
        }
        crate::audio::init(uid_text(a.allowed));
        boxm.keys_open();
        (mixer, boxm)
    }

    pub fn run() -> i32 {
        let a = match parse_args() {
            Ok(a) => a,
            Err(rc) => return rc,
        };
        let (ctl, card) = open_card(a.card);
        let ls = match crate::platform::bind_abstract(SOCK_NAME, libc::SOCK_STREAM, 2) {
            Ok(fd) => fd,
            Err(e) => {
                crate::loge!(
                    "socket @{}: {}",
                    String::from_utf8_lossy(SOCK_NAME),
                    crate::platform::err_text(&e)
                );
                return 1;
            }
        };
        let waitmask = unsafe { install_signals() };
        let (mixer, mut boxm) = start(&a, ctl, card);
        let uev = BoxState::uevent_open();
        boxm.announce();
        boxm.log_capture();
        crate::logi!(
            "hermessimd {} up: card {}, socket @{}, allowed uid {}",
            VERSION,
            card,
            String::from_utf8_lossy(SOCK_NAME),
            uid_text(a.allowed)
        );
        let mut d = Daemon {
            ls,
            uev,
            allowed: a.allowed,
            mixer,
            boxm,
            conns: [Conn::new(true), Conn::new(false)],
            heartbeat: crate::platform::now_sec() + 60,
        };
        let exit_code = main_loop(&mut d, &waitmask);
        d.shutdown(exit_code)
    }

    /// Poll until a stop signal, a poll error or a lost sound card; returns the exit code.
    fn main_loop(d: &mut Daemon, waitmask: &libc::sigset_t) -> i32 {
        while !STOP.load(Ordering::Relaxed) {
            let (mut fds, nk) = d.poll_set();
            let wait_ms = d.housekeeping();
            let to = libc::timespec {
                tv_sec: (wait_ms / 1000) as libc::time_t,
                tv_nsec: ((wait_ms % 1000) * 1_000_000) as libc::c_long,
            };
            let pr =
                unsafe { libc::ppoll(fds.as_mut_ptr(), (5 + nk) as libc::nfds_t, &to, waitmask) };
            if pr < 0 && std::io::Error::last_os_error().raw_os_error() != Some(libc::EINTR) {
                crate::loge!("poll: {}", crate::platform::errstr());
                break;
            }
            if pr <= 0 {
                continue;
            }
            if let Some(code) = d.dispatch(&fds, nk) {
                return code;
            }
        }
        0
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
