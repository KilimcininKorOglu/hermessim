// The call audio pump: PCM capture/injection through the phone's libtinyalsa, Opus, the jitter
// buffer and the audio client socket. GPL-3.0-or-later.
#![cfg(target_os = "android")]

use std::ffi::{CStr, CString};
use std::os::unix::io::RawFd;
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::Mutex;
use libc::{c_char, c_int, c_void};

use crate::alsa;
use crate::opus;

const SOCK_NAME: &[u8] = b"hermessimd-audio";
const CAP_RATE: i32 = 16000;
const CAP_FRAME: i32 = 160;
const CAP_PERIODS: u32 = 4;
const CAP_READ: i32 = CAP_FRAME;
const CAP_FRAMES_PER_READ: u32 = (CAP_READ / CAP_FRAME) as u32;
const CAP_MAX_ERRORS: u32 = 600;
const CAP_SLOW_AFTER: u32 = 5;
const DEC_RATE: i32 = 48000;
const MAX_PACKET: usize = 400;
const HDR: usize = 3;
const BITRATE: i32 = 24000;
const LOSS_PERC: i32 = 10;
const JB_SLOTS: usize = 64;
const JB_MASK: u16 = (JB_SLOTS - 1) as u16;
const JB_WINDOW: i32 = 200;
const JB_MAX: i16 = 8;
const VOICED_PEAK: i32 = 1000;

const LIVE_CAP: usize = 0;
const LIVE_PLAY: usize = 1;

pub fn opus_rate_ok(rate: i32) -> bool {
    matches!(rate, 8000 | 12000 | 16000 | 24000 | 48000)
}

#[derive(Clone, Copy)]
pub struct AudioCfg {
    pub card: i32,
    pub capture_pcm: i32,
    pub capture_rate: i32,
    pub capture_channels: i32,
    pub inject_pcm: i32,
    pub inject_rate: i32,
    pub inject_channels: i32,
    pub loopback: bool,
}

impl Default for AudioCfg {
    fn default() -> Self {
        Self {
            card: 0,
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

static CFG: Mutex<AudioCfg> = Mutex::new(AudioCfg {
    card: 0,
    capture_pcm: 0,
    capture_rate: 16000,
    capture_channels: 1,
    inject_pcm: 0,
    inject_rate: 48000,
    inject_channels: 2,
    loopback: false,
});

fn cfg() -> AudioCfg {
    *CFG.lock().unwrap()
}

// ------------------------------------------------------------------ libtinyalsa via dlopen

#[repr(C)]
struct Pcm {
    _p: [u8; 0],
}

#[repr(C)]
struct PcmConfig {
    channels: u32,
    rate: u32,
    period_size: u32,
    period_count: u32,
    format: c_int,
    start_threshold: u32,
    stop_threshold: u32,
    silence_threshold: u32,
    silence_size: u32,
    avail_min: c_int,
}

type PcmOpen = unsafe extern "C" fn(u32, u32, u32, *mut PcmConfig) -> *mut Pcm;
type PcmIsReady = unsafe extern "C" fn(*mut Pcm) -> c_int;
type PcmGetError = unsafe extern "C" fn(*mut Pcm) -> *const c_char;
type PcmRead = unsafe extern "C" fn(*mut Pcm, *mut c_void, u32) -> c_int;
type PcmWrite = unsafe extern "C" fn(*mut Pcm, *const c_void, u32) -> c_int;
type PcmClose = unsafe extern "C" fn(*mut Pcm) -> c_int;
type PcmPrepare = unsafe extern "C" fn(*mut Pcm) -> c_int;
type PcmStop = unsafe extern "C" fn(*mut Pcm) -> c_int;

#[derive(Clone, Copy)]
struct Ta {
    open: PcmOpen,
    is_ready: PcmIsReady,
    get_error: PcmGetError,
    read: PcmRead,
    write: PcmWrite,
    close: PcmClose,
    prepare: PcmPrepare,
    stop: Option<PcmStop>,
}

static TA: Mutex<Option<Ta>> = Mutex::new(None);

fn ta() -> Option<Ta> {
    let mut slot = TA.lock().unwrap();
    if let Some(t) = *slot {
        return Some(t);
    }
    let loaded = load_ta();
    *slot = loaded;
    loaded
}

unsafe fn symbol(h: *mut c_void, name: &[u8]) -> *mut c_void {
    libc::dlsym(h, name.as_ptr() as *const c_char)
}

fn load_ta() -> Option<Ta> {
    let libdir = if cfg!(target_pointer_width = "64") {
        "lib64"
    } else {
        "lib"
    };
    let paths = [
        format!("/system/{}/libtinyalsa.so", libdir),
        format!("/vendor/{}/libtinyalsa.so", libdir),
        format!("/system_ext/{}/libtinyalsa.so", libdir),
        format!("/odm/{}/libtinyalsa.so", libdir),
    ];
    let mut handle: *mut c_void = std::ptr::null_mut();
    for path in &paths {
        let Ok(c) = CString::new(path.as_str()) else {
            continue;
        };
        handle = unsafe { libc::dlopen(c.as_ptr(), libc::RTLD_NOW) };
        if !handle.is_null() {
            break;
        }
    }
    if handle.is_null() {
        let err = unsafe {
            let p = libc::dlerror();
            if p.is_null() {
                String::new()
            } else {
                CStr::from_ptr(p).to_string_lossy().into_owned()
            }
        };
        crate::loge!("dlopen libtinyalsa: {}", err);
        return None;
    }
    unsafe {
        let open = symbol(handle, b"pcm_open\0");
        let is_ready = symbol(handle, b"pcm_is_ready\0");
        let get_error = symbol(handle, b"pcm_get_error\0");
        let read = symbol(handle, b"pcm_read\0");
        let write = symbol(handle, b"pcm_write\0");
        let close = symbol(handle, b"pcm_close\0");
        let prepare = symbol(handle, b"pcm_prepare\0");
        let stop = symbol(handle, b"pcm_stop\0");
        if open.is_null()
            || is_ready.is_null()
            || get_error.is_null()
            || read.is_null()
            || write.is_null()
            || close.is_null()
            || prepare.is_null()
        {
            crate::loge!("libtinyalsa: missing symbols");
            return None;
        }
        Some(Ta {
            open: std::mem::transmute::<*mut c_void, PcmOpen>(open),
            is_ready: std::mem::transmute::<*mut c_void, PcmIsReady>(is_ready),
            get_error: std::mem::transmute::<*mut c_void, PcmGetError>(get_error),
            read: std::mem::transmute::<*mut c_void, PcmRead>(read),
            write: std::mem::transmute::<*mut c_void, PcmWrite>(write),
            close: std::mem::transmute::<*mut c_void, PcmClose>(close),
            prepare: std::mem::transmute::<*mut c_void, PcmPrepare>(prepare),
            stop: if stop.is_null() {
                None
            } else {
                Some(std::mem::transmute::<*mut c_void, PcmStop>(stop))
            },
        })
    }
}

#[derive(Clone, Copy)]
struct PcmPtr(*mut Pcm);
unsafe impl Send for PcmPtr {}

static LIVE: Mutex<[Option<PcmPtr>; 2]> = Mutex::new([None, None]);

fn pcm_hold(slot: usize, pcm: Option<PcmPtr>) -> Option<PcmPtr> {
    LIVE.lock().unwrap()[slot] = pcm;
    pcm
}

fn pcm_release(slot: usize, pcm: Option<PcmPtr>) {
    let Some(p) = pcm else { return };
    {
        let mut live = LIVE.lock().unwrap();
        if matches!(live[slot], Some(q) if q.0 == p.0) {
            live[slot] = None;
        }
    }
    if let Some(ta) = ta() {
        unsafe { (ta.close)(p.0) };
    }
}

fn pcm_wake_all() {
    let live = LIVE.lock().unwrap();
    if let Some(ta) = ta() {
        if let Some(stop) = ta.stop {
            for slot in live.iter().flatten() {
                unsafe { stop(slot.0) };
            }
        }
    }
}

fn open_pcm_n(
    device: i32,
    playback: bool,
    rate: i32,
    channels: i32,
    frame: i32,
    periods: u32,
) -> Option<PcmPtr> {
    let ta = ta()?;
    let mut c = PcmConfig {
        channels: channels as u32,
        rate: rate as u32,
        period_size: frame as u32,
        period_count: periods,
        format: 0,
        start_threshold: if playback { frame as u32 } else { 0 },
        stop_threshold: if playback { 0x7fff_ffff } else { 0 },
        silence_threshold: 0,
        silence_size: 0,
        avail_min: 0,
    };
    let flags = if playback { 0 } else { 0x1000_0000 };
    let pcm = unsafe { (ta.open)(cfg().card as u32, device as u32, flags, &mut c) };
    if pcm.is_null() {
        crate::loge!(
            "pcm_open card {} dev {} ({}): null",
            cfg().card,
            device,
            if playback { "out" } else { "in" }
        );
        return None;
    }
    if unsafe { (ta.is_ready)(pcm) } == 0 {
        let err = unsafe {
            let p = (ta.get_error)(pcm);
            if p.is_null() {
                String::new()
            } else {
                CStr::from_ptr(p).to_string_lossy().into_owned()
            }
        };
        crate::loge!(
            "pcm_open card {} dev {} ({}): {}",
            cfg().card,
            device,
            if playback { "out" } else { "in" },
            err
        );
        unsafe { (ta.close)(pcm) };
        return None;
    }
    Some(PcmPtr(pcm))
}

fn open_pcm(
    device: i32,
    playback: bool,
    rate: i32,
    channels: i32,
    frame: i32,
) -> Option<PcmPtr> {
    open_pcm_n(
        device,
        playback,
        rate,
        channels,
        frame,
        if playback { 2 } else { CAP_PERIODS },
    )
}

fn pcm_error(pcm: PcmPtr) -> String {
    let Some(ta) = ta() else {
        return String::new();
    };
    unsafe {
        let p = (ta.get_error)(pcm.0);
        if p.is_null() {
            String::new()
        } else {
            CStr::from_ptr(p).to_string_lossy().into_owned()
        }
    }
}

// ------------------------------------------------------------------ client socket

static CLIENT: Mutex<RawFd> = Mutex::new(-1);
static ALLOWED_UID: AtomicI32 = AtomicI32::new(-1);

fn client_set(fd: RawFd) {
    let old = {
        let mut client = CLIENT.lock().unwrap();
        let old = *client;
        *client = fd;
        old
    };
    if old >= 0 && old != fd {
        unsafe { libc::close(old) };
    }
}

fn client_send(buf: &[u8]) {
    let client = CLIENT.lock().unwrap();
    let fd = *client;
    if fd >= 0 {
        let n = unsafe {
            libc::send(
                fd,
                buf.as_ptr() as *const c_void,
                buf.len(),
                libc::MSG_DONTWAIT | libc::MSG_NOSIGNAL,
            )
        };
        if n < 0 {
            let errno = std::io::Error::last_os_error().raw_os_error().unwrap_or(0);
            if errno != libc::EAGAIN {
                crate::logw!(
                    "audio send: {}",
                    std::io::Error::from_raw_os_error(errno)
                );
            }
        }
    }
}

// ------------------------------------------------------------------ jitter buffer

#[derive(Clone, Copy)]
struct Slot {
    len: usize,
    seq: u16,
    data: [u8; MAX_PACKET],
}

const EMPTY_SLOT: Slot = Slot {
    len: 0,
    seq: 0,
    data: [0; MAX_PACKET],
};

struct Jitter {
    slots: [Slot; JB_SLOTS],
    valid: [bool; JB_SLOTS],
    next: u16,
    highest: u16,
    started: bool,
    playing: bool,
    win_min: i32,
    win_n: i32,
    trim: i32,
    consecutive_late: u32,
}

static JB: Mutex<Jitter> = Mutex::new(Jitter {
    slots: [EMPTY_SLOT; JB_SLOTS],
    valid: [false; JB_SLOTS],
    next: 0,
    highest: 0,
    started: false,
    playing: false,
    win_min: 1000,
    win_n: 0,
    trim: 0,
    consecutive_late: 0,
});

static JB_PRIME: AtomicI32 = AtomicI32::new(2);
static JB_KEEP: AtomicI32 = AtomicI32::new(1);
static PLAY_PERIOD: AtomicI32 = AtomicI32::new(480);
static STALL_MS: AtomicI32 = AtomicI32::new(0);
static CALL_RUNNING: AtomicBool = AtomicBool::new(false);

fn jb_reset() {
    let mut jb = JB.lock().unwrap();
    jb.valid = [false; JB_SLOTS];
    jb.started = false;
    jb.playing = false;
    jb.win_min = 1000;
    jb.win_n = 0;
    jb.trim = 0;
    jb.next = 0;
    jb.highest = 0;
}

fn jb_has(jb: &Jitter, seq: u16) -> bool {
    let i = (seq & JB_MASK) as usize;
    jb.valid[i] && jb.slots[i].seq == seq
}

fn jb_depth(jb: &Jitter) -> i16 {
    jb.highest.wrapping_sub(jb.next) as i16
}

fn jb_put(seq: u16, data: &[u8]) {
    let len = data.len();
    if len == 0 || len > MAX_PACKET {
        return;
    }
    let mut jb = JB.lock().unwrap();
    if !jb.started {
        jb.started = true;
        jb.next = seq;
        jb.highest = seq.wrapping_sub(1);
    }
    if (seq.wrapping_sub(jb.next) as i16) < 0 {
        st_inc(|s| &mut s.late);
        jb.consecutive_late += 1;
        if jb.consecutive_late >= 50 {
            jb.valid = [false; JB_SLOTS];
            jb.next = seq;
            jb.highest = seq.wrapping_sub(1);
            jb.playing = false;
            jb.consecutive_late = 0;
            crate::logw!("audio jitter buffer resync at seq {}", seq);
        } else {
            return;
        }
    }
    jb.consecutive_late = 0;
    let prime = JB_PRIME.load(Ordering::Relaxed);
    if (seq.wrapping_sub(jb.next) as i16) >= (JB_SLOTS - 1) as i16 {
        jb.next = seq.wrapping_sub(if prime > 1 { (prime - 1) as u16 } else { 0 });
        jb.playing = false;
    }
    let i = (seq & JB_MASK) as usize;
    jb.slots[i].len = len;
    jb.slots[i].seq = seq;
    jb.slots[i].data[..len].copy_from_slice(data);
    jb.valid[i] = true;
    if (seq.wrapping_sub(jb.highest) as i16) > 0 {
        jb.highest = seq;
    }
    if !jb.playing && jb_depth(&jb) + 1 >= prime as i16 {
        jb.playing = true;
    }
    st_inc(|s| &mut s.recv);
}

fn jb_take(out: &mut [u8; MAX_PACKET], outlen: &mut usize, mode: &mut i32) -> bool {
    let mut jb = JB.lock().unwrap();
    if !jb.playing {
        return false;
    }
    if jb_depth(&jb) < 0 {
        jb.playing = false;
        return false;
    }
    let next = jb.next;
    if jb_has(&jb, next) {
        let i = (next & JB_MASK) as usize;
        let len = jb.slots[i].len;
        out[..len].copy_from_slice(&jb.slots[i].data[..len]);
        *outlen = len;
        *mode = 0;
        jb.valid[i] = false;
    } else if jb_has(&jb, next.wrapping_add(1)) {
        let i = (next.wrapping_add(1) & JB_MASK) as usize;
        let len = jb.slots[i].len;
        out[..len].copy_from_slice(&jb.slots[i].data[..len]);
        *outlen = len;
        *mode = 1;
        st_inc(|s| &mut s.fec);
    } else {
        *mode = 2;
        st_inc(|s| &mut s.plc);
    }
    jb.next = next.wrapping_add(1);
    let d = jb_depth(&jb) as i32;
    st_jb_depth(d);
    if d < jb.win_min {
        jb.win_min = d;
    }
    jb.win_n += 1;
    if jb.win_n >= JB_WINDOW {
        let keep = JB_KEEP.load(Ordering::Relaxed);
        if jb.win_min > keep {
            jb.trim = jb.win_min - keep;
        }
        jb.win_min = 1000;
        jb.win_n = 0;
    }
    let keep = JB_KEEP.load(Ordering::Relaxed);
    if jb.trim > 0 && jb_depth(&jb) as i32 > keep {
        let i = (jb.next & JB_MASK) as usize;
        jb.valid[i] = false;
        jb.next = jb.next.wrapping_add(1);
        jb.trim -= 1;
        st_inc(|s| &mut s.catchup);
    }
    if jb_depth(&jb) > JB_MAX {
        let i = (jb.next & JB_MASK) as usize;
        jb.valid[i] = false;
        jb.next = jb.next.wrapping_add(1);
        st_inc(|s| &mut s.catchup);
    }
    true
}

// ------------------------------------------------------------------ statistics

struct Stats {
    sent: u32,
    recv: u32,
    plc: u32,
    fec: u32,
    late: u32,
    catchup: u32,
    caperr: u32,
    peak: i32,
    played: u32,
    depth_max: u32,
    jb_sum: f64,
    jb_n: u32,
    reopen: u32,
    tot_cap: u32,
    tot_voiced: u32,
    tot_sent: u32,
    tot_played: u32,
    tot_peak: i32,
}

const EMPTY_STATS: Stats = Stats {
    sent: 0,
    recv: 0,
    plc: 0,
    fec: 0,
    late: 0,
    catchup: 0,
    caperr: 0,
    peak: 0,
    played: 0,
    depth_max: 0,
    jb_sum: 0.0,
    jb_n: 0,
    reopen: 0,
    tot_cap: 0,
    tot_voiced: 0,
    tot_sent: 0,
    tot_played: 0,
    tot_peak: 0,
};

static STATS: Mutex<Stats> = Mutex::new(EMPTY_STATS);

fn st_inc(f: impl FnOnce(&mut Stats) -> &mut u32) {
    let mut stats = STATS.lock().unwrap();
    let v = f(&mut stats);
    *v += 1;
}

fn st_add(f: impl FnOnce(&mut Stats) -> &mut u32, n: u32) {
    let mut stats = STATS.lock().unwrap();
    let v = f(&mut stats);
    *v += n;
}

fn st_peak(f: impl FnOnce(&mut Stats) -> &mut i32, peak: i32) {
    let mut stats = STATS.lock().unwrap();
    let v = f(&mut stats);
    if peak > *v {
        *v = peak;
    }
}

fn st_umax(f: impl FnOnce(&mut Stats) -> &mut u32, value: u32) {
    let mut stats = STATS.lock().unwrap();
    let v = f(&mut stats);
    if value > *v {
        *v = value;
    }
}

fn st_jb_depth(d: i32) {
    let mut stats = STATS.lock().unwrap();
    stats.jb_sum += if d < 0 { 0.0 } else { d as f64 };
    stats.jb_n += 1;
}

// ------------------------------------------------------------------ io thread

fn io_main() {
    let ls = match crate::platform::bind_abstract(SOCK_NAME, libc::SOCK_SEQPACKET, 1) {
        Ok(fd) => fd,
        Err(e) => {
            crate::loge!(
                "audio socket @{}: {}",
                String::from_utf8_lossy(SOCK_NAME),
                e
            );
            return;
        }
    };
    crate::logi!(
        "audio socket @{} listening",
        String::from_utf8_lossy(SOCK_NAME)
    );

    let mut buf = [0u8; HDR + MAX_PACKET];
    let mut fd: RawFd = -1;
    loop {
        let mut pf = [
            libc::pollfd {
                fd: ls,
                events: libc::POLLIN,
                revents: 0,
            },
            libc::pollfd {
                fd,
                events: libc::POLLIN,
                revents: 0,
            },
        ];
        let n = unsafe { libc::poll(pf.as_mut_ptr(), if fd >= 0 { 2 } else { 1 }, -1) };
        if n < 0 {
            if std::io::Error::last_os_error().raw_os_error() == Some(libc::EINTR) {
                continue;
            }
            break;
        }
        if pf[0].revents & libc::POLLIN != 0 {
            let nfd = unsafe { libc::accept4(ls, std::ptr::null_mut(), std::ptr::null_mut(), libc::SOCK_CLOEXEC) };
            if nfd >= 0 {
                let mut cr: libc::ucred = unsafe { std::mem::zeroed() };
                let mut len = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
                let ok = unsafe {
                    libc::getsockopt(
                        nfd,
                        libc::SOL_SOCKET,
                        libc::SO_PEERCRED,
                        &mut cr as *mut libc::ucred as *mut c_void,
                        &mut len,
                    )
                } == 0;
                let uid = cr.uid as i32;
                if !ok || (cr.uid != 0 && uid != ALLOWED_UID.load(Ordering::Relaxed)) {
                    crate::logw!(
                        "audio client uid={} REJECTED",
                        if len != 0 { uid } else { -1 }
                    );
                    unsafe { libc::close(nfd) };
                } else {
                    crate::logi!(
                        "audio client connected (uid={}){}",
                        uid,
                        if fd >= 0 {
                            ", replacing the previous one"
                        } else {
                            ""
                        }
                    );
                    client_set(nfd);
                    fd = nfd;
                    jb_reset();
                    continue;
                }
            }
        }
        if fd >= 0 && pf[1].revents & (libc::POLLIN | libc::POLLHUP | libc::POLLERR) != 0 {
            let got = unsafe {
                libc::recv(
                    fd,
                    buf.as_mut_ptr() as *mut c_void,
                    buf.len(),
                    0,
                )
            };
            if got <= 0 {
                crate::logi!("audio client gone");
                let mine = {
                    let mut client = CLIENT.lock().unwrap();
                    let mine = *client == fd;
                    if mine {
                        *client = -1;
                    }
                    mine
                };
                if mine {
                    unsafe { libc::close(fd) };
                }
                fd = -1;
            } else if got as usize >= HDR {
                let seq = u16::from_le_bytes([buf[0], buf[1]]);
                jb_put(seq, &buf[HDR..got as usize]);
            }
        }
    }
    unsafe { libc::close(ls) };
}

// ------------------------------------------------------------------ capture

fn log_throttle(last: &mut i64, secs: i64) -> bool {
    let now = crate::platform::now_sec();
    if now - *last < secs {
        return false;
    }
    *last = now;
    true
}

fn set_realtime(who: &str) {
    let sp = libc::sched_param { sched_priority: 2 };
    let rc = unsafe { libc::pthread_setschedparam(libc::pthread_self(), libc::SCHED_FIFO, &sp) };
    if rc != 0 {
        crate::logw!("{}: SCHED_FIFO refused (ok)", who);
    }
}

fn capture_main() {
    set_realtime("capture");
    let cfg = cfg();
    let mut crate_ = if opus_rate_ok(cfg.capture_rate) {
        cfg.capture_rate
    } else {
        CAP_RATE
    };
    let mut cch = if cfg.capture_channels == 2 { 2 } else { 1 };
    if let Some((r, c)) = alsa::pcm_negotiate(
        cfg.card,
        cfg.capture_pcm,
        false,
        crate_,
        cch,
    ) {
        crate_ = r;
        cch = c;
    }
    if !opus_rate_ok(crate_) {
        crate_ = CAP_RATE;
    }
    if !(1..=2).contains(&cch) {
        cch = 1;
    }
    let cframe = crate_ / 100;
    let mut enc = match opus::Encoder::new(crate_, 1, opus::APPLICATION_VOIP) {
        Some(e) => e,
        None => return,
    };
    enc.ctl(opus::SET_BITRATE, BITRATE);
    enc.ctl(opus::SET_INBAND_FEC, 1);
    enc.ctl(opus::SET_PACKET_LOSS_PERC, LOSS_PERC);
    enc.ctl(opus::SET_SIGNAL, opus::SIGNAL_VOICE);

    let mut pcm = pcm_hold(
        LIVE_CAP,
        open_pcm(cfg.capture_pcm, false, crate_, cch, cframe),
    );
    if pcm.is_none() {
        return;
    }
    let Some(ta) = ta() else {
        pcm_release(LIVE_CAP, pcm);
        return;
    };

    let mut pcmbuf = [0i16; 480 * 2];
    let mut pkt = [0u8; HDR + MAX_PACKET];
    let mut seq: u16 = 0;
    let mut t0 = crate::platform::now_ns(libc::CLOCK_MONOTONIC);
    let mut frames: u32 = 0;

    let mut err_log: i64 = 0;
    let mut consecutive_err: u32 = 0;
    while CALL_RUNNING.load(Ordering::Relaxed) {
        if pcm.is_none() {
            pcm = pcm_hold(
                LIVE_CAP,
                open_pcm(cfg.capture_pcm, false, crate_, cch, cframe),
            );
            if pcm.is_none() {
                consecutive_err += 1;
                if consecutive_err >= CAP_MAX_ERRORS {
                    crate::loge!("capture: cannot reopen, giving up for this call");
                    break;
                }
                crate::platform::sleep_ns(100_000_000);
                continue;
            }
        }
        let p = pcm.unwrap();
        let rc = unsafe {
            (ta.read)(
                p.0,
                pcmbuf.as_mut_ptr() as *mut c_void,
                (cframe * cch * 2) as u32,
            )
        };
        if rc != 0 {
            if log_throttle(&mut err_log, 2) {
                crate::logw!("capture read failed: {}", pcm_error(p));
            }
            consecutive_err += 1;
            if consecutive_err >= CAP_MAX_ERRORS {
                crate::loge!(
                    "capture: {} consecutive errors, giving up for this call (protecting the audio DSP)",
                    consecutive_err
                );
                break;
            }
            st_inc(|s| &mut s.caperr);
            if consecutive_err >= CAP_SLOW_AFTER {
                crate::platform::sleep_ns(100_000_000);
            }
            seq = seq.wrapping_add(CAP_FRAMES_PER_READ as u16);
            if consecutive_err == 1 {
                continue;
            }
            pcm_release(LIVE_CAP, pcm);
            crate::platform::sleep_ns(10_000_000);
            pcm = pcm_hold(
                LIVE_CAP,
                open_pcm(cfg.capture_pcm, false, crate_, cch, cframe),
            );
            continue;
        }
        consecutive_err = 0;
        if cch == 2 {
            for i in 0..cframe as usize {
                pcmbuf[i] = ((pcmbuf[2 * i] as i32 + pcmbuf[2 * i + 1] as i32) / 2) as i16;
            }
        }
        let mut rpeak = 0i32;
        for &s in pcmbuf.iter().take(cframe as usize) {
            let a = if s < 0 { -(s as i32) } else { s as i32 };
            if a > rpeak {
                rpeak = a;
            }
        }
        st_peak(|s| &mut s.peak, rpeak);
        st_peak(|s| &mut s.tot_peak, rpeak);
        st_add(|s| &mut s.tot_cap, CAP_FRAMES_PER_READ);
        if rpeak > VOICED_PEAK {
            st_add(|s| &mut s.tot_voiced, CAP_FRAMES_PER_READ);
        }
        for f in 0..CAP_FRAMES_PER_READ as usize {
            let start = f * cframe as usize;
            let len = enc.encode(
                &pcmbuf[start..start + cframe as usize],
                cframe,
                &mut pkt[HDR..],
            );
            if len < 0 {
                crate::logw!("opus encode: {}", len);
                continue;
            }
            if len <= 2 {
                seq = seq.wrapping_add(1);
                continue;
            }
            pkt[0] = (seq & 0xff) as u8;
            pkt[1] = (seq >> 8) as u8;
            pkt[2] = 0;
            client_send(&pkt[..HDR + len as usize]);
            if cfg.loopback {
                jb_put(seq, &pkt[HDR..HDR + len as usize]);
            }
            seq = seq.wrapping_add(1);
            st_inc(|s| &mut s.sent);
            st_inc(|s| &mut s.tot_sent);
        }
        frames += CAP_FRAMES_PER_READ;
        if frames >= 500 {
            let now = crate::platform::now_ns(libc::CLOCK_MONOTONIC);
            let secs = (now - t0) as f64 / 1e9;
            let (
                sent,
                recv,
                fec,
                plc,
                late,
                catchup,
                caperr,
                peak,
                played,
                depth_max,
                reopen,
                jb_n,
                jb_sum,
            ) = {
                let mut s = STATS.lock().unwrap();
                let snap = (
                    s.sent, s.recv, s.fec, s.plc, s.late, s.catchup, s.caperr, s.peak,
                    s.played, s.depth_max, s.reopen, s.jb_n, s.jb_sum,
                );
                s.jb_sum = 0.0;
                s.jb_n = 0;
                s.reopen = 0;
                s.sent = 0;
                s.recv = 0;
                s.fec = 0;
                s.plc = 0;
                s.late = 0;
                s.catchup = 0;
                s.caperr = 0;
                s.peak = 0;
                s.played = 0;
                s.depth_max = 0;
                snap
            };
            crate::logi!(
                "audio 5s: sent={} recv={} fec={} plc={} late={} catchup={} caperr={} peak={} played={} depthmax={} ({:.1} fps) | held: jb={:.1}ms reopen={} (tune prime={} keep={} period={})",
                sent,
                recv,
                fec,
                plc,
                late,
                catchup,
                caperr,
                peak,
                played,
                depth_max,
                frames as f64 / secs,
                if jb_n != 0 { jb_sum / jb_n as f64 * 10.0 } else { -1.0 },
                reopen,
                JB_PRIME.load(Ordering::Relaxed),
                JB_KEEP.load(Ordering::Relaxed),
                PLAY_PERIOD.load(Ordering::Relaxed)
            );
            frames = 0;
            t0 = now;
        }
    }
    pcm_release(LIVE_CAP, pcm);
}

// ------------------------------------------------------------------ playback

fn playback_main() {
    set_realtime("playback");
    let cfg = cfg();
    let want = if opus_rate_ok(cfg.inject_rate) {
        cfg.inject_rate
    } else {
        DEC_RATE
    };
    if want != cfg.inject_rate {
        crate::logw!(
            "inject rate {} isn't an Opus rate: playing at {}",
            cfg.inject_rate,
            want
        );
    }
    let mut drate = want;
    let mut ich = if cfg.inject_channels == 2 { 2 } else { 1 };
    if let Some((r, c)) = alsa::pcm_negotiate(cfg.card, cfg.inject_pcm, true, want, ich) {
        drate = r;
        ich = c;
    }
    if !opus_rate_ok(drate) {
        drate = DEC_RATE;
    }
    if !(1..=2).contains(&ich) {
        ich = 1;
    }
    let mut dframe = drate / 100;
    let mut dec = match opus::Decoder::new(drate, 1) {
        Some(d) => d,
        None => return,
    };

    let mut base = PLAY_PERIOD.load(Ordering::Relaxed);
    let mut period = base * drate / 48000;
    let mut pcm = pcm_hold(
        LIVE_PLAY,
        open_pcm(cfg.inject_pcm, true, drate, ich, period),
    );
    let mut tries = 1;
    while pcm.is_none() && CALL_RUNNING.load(Ordering::Relaxed) {
        let wait = if tries > 85 {
            1_000_000_000
        } else if tries > 25 {
            500_000_000
        } else {
            200_000_000
        };
        crate::platform::sleep_ns(wait);
        if let Some((r2, c2)) = alsa::pcm_negotiate(cfg.card, cfg.inject_pcm, true, want, ich) {
            if (r2 != drate || c2 != ich) && opus_rate_ok(r2) && (1..=2).contains(&c2) {
                if let Some(d2) = opus::Decoder::new(r2, 1) {
                    dec = d2;
                    drate = r2;
                    ich = c2;
                    dframe = drate / 100;
                    period = base * drate / 48000;
                }
            }
        }
        pcm = pcm_hold(
            LIVE_PLAY,
            open_pcm_n(cfg.inject_pcm, true, drate, ich, period, 2),
        );
        if pcm.is_some() {
            crate::logi!("inject PCM opened after {} tries", tries + 1);
        }
        tries += 1;
    }
    if pcm.is_none() {
        return;
    }
    jb_reset();

    let Some(ta) = ta() else {
        pcm_release(LIVE_PLAY, pcm);
        return;
    };

    let mut mono = [0i16; 480];
    let mut out = [0i16; 480 * 2];
    let mut pkt = [0u8; MAX_PACKET];
    let mut periods: u32 = 2;
    let mut win_frames: u32 = 0;
    let mut slow_windows: u32 = 0;
    let mut win_t0 = crate::platform::now_ns(libc::CLOCK_MONOTONIC);
    let mut werr_log: i64 = 0;

    while CALL_RUNNING.load(Ordering::Relaxed) {
        let tuned = PLAY_PERIOD.load(Ordering::Relaxed);
        if base != tuned {
            pcm_release(LIVE_PLAY, pcm);
            base = tuned;
            period = base * drate / 48000;
            pcm = pcm_hold(
                LIVE_PLAY,
                open_pcm_n(cfg.inject_pcm, true, drate, ich, period, periods),
            );
            crate::logi!(
                "inject PCM reopened with {}-frame periods: {}",
                period,
                if pcm.is_some() { "ok" } else { "FAILED" }
            );
            if pcm.is_none() {
                PLAY_PERIOD.store(480, Ordering::Relaxed);
                base = 480;
                period = dframe;
                pcm = pcm_hold(
                    LIVE_PLAY,
                    open_pcm(cfg.inject_pcm, true, drate, ich, dframe),
                );
                if pcm.is_none() {
                    break;
                }
            }
        }
        let mut len = 0usize;
        let mut mode = 2i32;
        let have = jb_take(&mut pkt, &mut len, &mut mode);
        let samples: i32;
        if !have {
            samples = dframe;
            mono = [0i16; 480];
        } else if mode == 0 {
            samples = dec.decode(Some(&pkt[..len]), &mut mono, false);
        } else if mode == 1 {
            samples = dec.decode(Some(&pkt[..len]), &mut mono, true);
        } else {
            samples = dec.decode(None, &mut mono, false);
        }
        let samples = if samples < 0 {
            mono = [0i16; 480];
            dframe
        } else {
            samples
        };

        st_inc(|s| &mut s.played);
        st_inc(|s| &mut s.tot_played);
        {
            let jb = JB.lock().unwrap();
            let d = jb_depth(&jb);
            if jb.playing {
                st_umax(|s| &mut s.depth_max, if d < 0 { 0 } else { d as u32 });
            }
        }
        let p = pcm.unwrap();
        let wrote_ok = if ich == 2 {
            for i in 0..samples as usize {
                out[2 * i] = mono[i];
                out[2 * i + 1] = mono[i];
            }
            (unsafe {
                (ta.write)(
                    p.0,
                    out.as_ptr() as *const c_void,
                    (samples as usize * 2 * 2) as u32,
                )
            }) == 0
        } else {
            (unsafe {
                (ta.write)(
                    p.0,
                    mono.as_ptr() as *const c_void,
                    (samples as usize * 2) as u32,
                )
            }) == 0
        };
        if !wrote_ok {
            if log_throttle(&mut werr_log, 2) {
                crate::logw!("inject write: {}", pcm_error(p));
            }
            unsafe { (ta.prepare)(p.0) };
            crate::platform::sleep_ns(10_000_000);
        }

        win_frames += 1;
        if win_frames >= 100 {
            let now = crate::platform::now_ns(libc::CLOCK_MONOTONIC);
            let secs = (now - win_t0) as f64 / 1e9;
            slow_windows = if secs > 100.0 / 90.0 {
                slow_windows + 1
            } else {
                0
            };
            if slow_windows >= 2 {
                if periods < 4 {
                    periods += 1;
                }
                crate::logw!(
                    "inject stream running at {:.0} frames/s: reopening with {} periods",
                    100.0 / secs,
                    periods
                );
                pcm_release(LIVE_PLAY, pcm);
                pcm = pcm_hold(
                    LIVE_PLAY,
                    open_pcm_n(cfg.inject_pcm, true, drate, ich, period, periods),
                );
                st_inc(|s| &mut s.reopen);
                slow_windows = 0;
                if pcm.is_none() {
                    break;
                }
            }
            win_frames = 0;
            win_t0 = now;
        }

        let stall = STALL_MS.swap(0, Ordering::Relaxed);
        if stall > 0 {
            crate::logw!("debug stall {} ms", stall);
            crate::platform::sleep_ns(stall as i64 * 1_000_000);
        }
    }
    pcm_release(LIVE_PLAY, pcm);
}

// ------------------------------------------------------------------ lifecycle

static CAP_HANDLE: Mutex<Option<std::thread::JoinHandle<()>>> = Mutex::new(None);
static PLAY_HANDLE: Mutex<Option<std::thread::JoinHandle<()>>> = Mutex::new(None);

pub fn init(allowed_uid: i32) {
    ALLOWED_UID.store(allowed_uid, Ordering::Relaxed);
    match std::thread::Builder::new()
        .name("audio-io".into())
        .spawn(io_main)
    {
        Ok(_) => {}
        Err(e) => crate::loge!("audio io thread: {}", e),
    }
}

pub fn tune(prime: i32, keep: i32, period: i32) -> bool {
    if crate::protocol::tune_values(prime, keep, period).is_none() {
        return false;
    }
    JB_PRIME.store(prime, Ordering::Relaxed);
    JB_KEEP.store(keep, Ordering::Relaxed);
    PLAY_PERIOD.store(period, Ordering::Relaxed);
    crate::logi!("audio tune: prime={} keep={} period={}", prime, keep, period);
    true
}

pub fn stall(ms: i32) {
    if ms > 0 && ms <= 500 {
        STALL_MS.store(ms, Ordering::Relaxed);
    }
}

pub fn tune_get() -> String {
    format!(
        "prime={} keep={} period={}",
        JB_PRIME.load(Ordering::Relaxed),
        JB_KEEP.load(Ordering::Relaxed),
        PLAY_PERIOD.load(Ordering::Relaxed)
    )
}

pub fn totals() -> String {
    let s = STATS.lock().unwrap();
    format!(
        "cap={} voiced={} sent={} played={} peak={}",
        s.tot_cap, s.tot_voiced, s.tot_sent, s.tot_played, s.tot_peak
    )
}

pub fn call_begin(cfg: AudioCfg) {
    if CALL_RUNNING.load(Ordering::Relaxed) {
        call_end();
    }
    {
        let mut s = STATS.lock().unwrap();
        s.tot_cap = 0;
        s.tot_voiced = 0;
        s.tot_sent = 0;
        s.tot_played = 0;
        s.tot_peak = 0;
    }
    *CFG.lock().unwrap() = cfg;
    jb_reset();
    CALL_RUNNING.store(true, Ordering::Relaxed);
    let cap = std::thread::Builder::new()
        .name("audio-cap".into())
        .spawn(capture_main);
    match cap {
        Ok(h) => *CAP_HANDLE.lock().unwrap() = Some(h),
        Err(e) => crate::loge!("capture thread: {}", e),
    }
    let play = std::thread::Builder::new()
        .name("audio-play".into())
        .spawn(playback_main);
    match play {
        Ok(h) => *PLAY_HANDLE.lock().unwrap() = Some(h),
        Err(e) => crate::loge!("playback thread: {}", e),
    }
    crate::logi!("audio call begin (loopback={})", cfg.loopback as i32);
}

pub fn call_end() {
    if !CALL_RUNNING.load(Ordering::Relaxed) {
        return;
    }
    CALL_RUNNING.store(false, Ordering::Relaxed);
    pcm_wake_all();
    let cap = CAP_HANDLE.lock().unwrap().take();
    if let Some(h) = cap {
        let _ = h.join();
    }
    let play = PLAY_HANDLE.lock().unwrap().take();
    if let Some(h) = play {
        let _ = h.join();
    }
    crate::logi!("audio call end: {}", totals());
}

pub fn shutdown() {
    call_end();
}
