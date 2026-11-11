// The call audio pump: PCM capture/injection through the phone's libtinyalsa, Opus, the jitter
// buffer and the audio client socket. GPL-3.0-or-later.
#![cfg(target_os = "android")]

use libc::{c_char, c_int, c_void};
use std::ffi::{CStr, CString};
use std::os::unix::io::RawFd;
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::{Mutex, MutexGuard, PoisonError};

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

/// Take a lock even when a thread panicked while holding it: the guarded values are plain counters
/// and handles that stay valid, and one failed call must not stop the audio of every later call.
fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(PoisonError::into_inner)
}

fn cfg() -> AudioCfg {
    *lock(&CFG)
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
    let mut slot = lock(&TA);
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
    lock(&LIVE)[slot] = pcm;
    pcm
}

fn pcm_release(slot: usize, pcm: Option<PcmPtr>) {
    let Some(p) = pcm else { return };
    {
        let mut live = lock(&LIVE);
        if matches!(live[slot], Some(q) if q.0 == p.0) {
            live[slot] = None;
        }
    }
    if let Some(ta) = ta() {
        unsafe { (ta.close)(p.0) };
    }
}

fn pcm_wake_all() {
    let live = lock(&LIVE);
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

fn open_pcm(device: i32, playback: bool, rate: i32, channels: i32, frame: i32) -> Option<PcmPtr> {
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
/// A client is connected: the per-call loss totals count only then (a hang-up's tail isn't a loss).
static CLIENT_UP: AtomicBool = AtomicBool::new(false);
static ALLOWED_UID: AtomicI32 = AtomicI32::new(-1);

fn client_set(fd: RawFd) {
    let old = {
        let mut client = lock(&CLIENT);
        let old = *client;
        *client = fd;
        CLIENT_UP.store(fd >= 0, Ordering::Relaxed);
        old
    };
    if old >= 0 && old != fd {
        unsafe { libc::close(old) };
    }
}

fn client_send(buf: &[u8]) {
    let client = lock(&CLIENT);
    let fd = *client;
    if fd < 0 {
        st_inc(|s| &mut s.tot_noclient);
        return;
    }
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
        if errno == libc::EAGAIN || errno == libc::EWOULDBLOCK {
            // The client's socket had no room: its reader fell behind and this packet is dropped.
            st_inc(|s| &mut s.txdrop);
            st_inc(|s| &mut s.tot_txdrop);
        } else {
            crate::logw!("audio send: {}", crate::platform::strerror(errno));
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
    let mut jb = lock(&JB);
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
    let mut jb = lock(&JB);
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
    st_inc(|s| &mut s.tot_recv);
}

/// The jitter buffer saw this client's first packet: the per-call loss totals start there.
fn jb_started() -> bool {
    lock(&JB).started
}

fn jb_take(out: &mut [u8; MAX_PACKET], outlen: &mut usize, mode: &mut i32) -> bool {
    let mut jb = lock(&JB);
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
        st_inc(|s| &mut s.tot_plc);
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
    txdrop: u32,
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
    tot_txdrop: u32,
    tot_noclient: u32,
    tot_recv: u32,
    tot_plc: u32,
    tot_starved: u32,
    tot_live: u32,
    tot_capms: u32,
    tot_caperr: u32,
}

const EMPTY_STATS: Stats = Stats {
    sent: 0,
    txdrop: 0,
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
    tot_txdrop: 0,
    tot_noclient: 0,
    tot_recv: 0,
    tot_plc: 0,
    tot_starved: 0,
    tot_live: 0,
    tot_capms: 0,
    tot_caperr: 0,
};

static STATS: Mutex<Stats> = Mutex::new(EMPTY_STATS);

fn st_inc(f: impl FnOnce(&mut Stats) -> &mut u32) {
    let mut stats = lock(&STATS);
    let v = f(&mut stats);
    *v += 1;
}

fn st_add(f: impl FnOnce(&mut Stats) -> &mut u32, n: u32) {
    let mut stats = lock(&STATS);
    let v = f(&mut stats);
    *v += n;
}

fn st_peak(f: impl FnOnce(&mut Stats) -> &mut i32, peak: i32) {
    let mut stats = lock(&STATS);
    let v = f(&mut stats);
    if peak > *v {
        *v = peak;
    }
}

fn st_umax(f: impl FnOnce(&mut Stats) -> &mut u32, value: u32) {
    let mut stats = lock(&STATS);
    let v = f(&mut stats);
    if value > *v {
        *v = value;
    }
}

/// The 5 s counters as one window, cleared under the same lock so no count is lost between read and reset.
fn take_window(fps: f64) -> crate::protocol::AudioWindow {
    let mut s = lock(&STATS);
    let w = crate::protocol::AudioWindow {
        sent: s.sent,
        txdrop: s.txdrop,
        recv: s.recv,
        fec: s.fec,
        plc: s.plc,
        late: s.late,
        catchup: s.catchup,
        caperr: s.caperr,
        peak: s.peak,
        played: s.played,
        depth_max: s.depth_max,
        fps,
        jb_ms: if s.jb_n != 0 {
            s.jb_sum / s.jb_n as f64 * 10.0
        } else {
            -1.0
        },
        reopen: s.reopen,
        prime: JB_PRIME.load(Ordering::Relaxed),
        keep: JB_KEEP.load(Ordering::Relaxed),
        period: PLAY_PERIOD.load(Ordering::Relaxed),
    };
    s.jb_sum = 0.0;
    s.jb_n = 0;
    s.reopen = 0;
    s.sent = 0;
    s.txdrop = 0;
    s.recv = 0;
    s.fec = 0;
    s.plc = 0;
    s.late = 0;
    s.catchup = 0;
    s.caperr = 0;
    s.peak = 0;
    s.played = 0;
    s.depth_max = 0;
    w
}

fn st_jb_depth(d: i32) {
    let mut stats = lock(&STATS);
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
                crate::platform::err_text(&e)
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
            if let Some(nfd) = accept_client(ls, fd) {
                fd = nfd;
                continue;
            }
        }
        if fd >= 0 && pf[1].revents & (libc::POLLIN | libc::POLLHUP | libc::POLLERR) != 0 {
            fd = read_client(fd, &mut buf);
        }
    }
    unsafe { libc::close(ls) };
}

/// Accept one audio client: root or the app's uid (SO_PEERCRED) replaces the current client `fd`;
/// any other peer is closed. Returns the new client socket.
fn accept_client(ls: RawFd, fd: RawFd) -> Option<RawFd> {
    let nfd = unsafe {
        libc::accept4(
            ls,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            libc::SOCK_CLOEXEC,
        )
    };
    if nfd < 0 {
        return None;
    }
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
        return None;
    }
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
    jb_reset();
    Some(nfd)
}

/// Read one packet from the client into the jitter buffer. Returns the client socket, or -1 when
/// the client is gone (the socket is closed if it is still the current client).
fn read_client(fd: RawFd, buf: &mut [u8]) -> RawFd {
    let got = unsafe { libc::recv(fd, buf.as_mut_ptr() as *mut c_void, buf.len(), 0) };
    if got > 0 {
        if got as usize >= HDR {
            let seq = u16::from_le_bytes([buf[0], buf[1]]);
            jb_put(seq, &buf[HDR..got as usize]);
        }
        return fd;
    }
    crate::logi!("audio client gone");
    let mine = {
        let mut client = lock(&CLIENT);
        let mine = *client == fd;
        if mine {
            *client = -1;
            CLIENT_UP.store(false, Ordering::Relaxed);
        }
        mine
    };
    if mine {
        unsafe { libc::close(fd) };
    }
    -1
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
    let (rate, ch) = capture_format(&cfg);
    let Some(enc) = capture_encoder(rate) else {
        return;
    };
    let pcm = pcm_hold(
        LIVE_CAP,
        open_pcm(cfg.capture_pcm, false, rate, ch, rate / 100),
    );
    if pcm.is_none() {
        return;
    }
    let Some(ta) = ta() else {
        pcm_release(LIVE_CAP, pcm);
        return;
    };
    let mut cap = Capture {
        cfg,
        rate,
        ch,
        frame: rate / 100,
        enc,
        ta,
        pcm,
        pcmbuf: [0i16; 480 * 2],
        pkt: [0u8; HDR + MAX_PACKET],
        seq: 0,
        t0: crate::platform::now_ns(libc::CLOCK_MONOTONIC),
        frames: 0,
        err_log: 0,
        consecutive_err: 0,
        clock: crate::protocol::CapClock::default(),
    };
    while CALL_RUNNING.load(Ordering::Relaxed) {
        if !cap.step() {
            break;
        }
    }
    pcm_release(LIVE_CAP, cap.pcm);
}

/// The capture rate and channel count: the profile's (an Opus rate, mono or stereo), refined by
/// what the PCM accepts now, then forced back to 16 kHz / mono if that left the Opus range.
fn capture_format(cfg: &AudioCfg) -> (i32, i32) {
    let mut rate = if opus_rate_ok(cfg.capture_rate) {
        cfg.capture_rate
    } else {
        CAP_RATE
    };
    let mut ch = if cfg.capture_channels == 2 { 2 } else { 1 };
    if let Some((r, c)) = alsa::pcm_negotiate(cfg.card, cfg.capture_pcm, false, rate, ch) {
        rate = r;
        ch = c;
    }
    if !opus_rate_ok(rate) {
        rate = CAP_RATE;
    }
    if !(1..=2).contains(&ch) {
        ch = 1;
    }
    (rate, ch)
}

fn capture_encoder(rate: i32) -> Option<opus::Encoder> {
    let enc = opus::Encoder::new(rate, 1, opus::APPLICATION_VOIP)?;
    enc.ctl(opus::SET_BITRATE, BITRATE);
    enc.ctl(opus::SET_INBAND_FEC, 1);
    enc.ctl(opus::SET_PACKET_LOSS_PERC, LOSS_PERC);
    enc.ctl(opus::SET_SIGNAL, opus::SIGNAL_VOICE);
    Some(enc)
}

/// The capture thread's state for one call.
struct Capture {
    cfg: AudioCfg,
    rate: i32,
    ch: i32,
    frame: i32,
    enc: opus::Encoder,
    ta: Ta,
    pcm: Option<PcmPtr>,
    pcmbuf: [i16; 480 * 2],
    pkt: [u8; HDR + MAX_PACKET],
    seq: u16,
    t0: i64,
    frames: u32,
    err_log: i64,
    consecutive_err: u32,
    clock: crate::protocol::CapClock,
}

impl Capture {
    fn open(&self) -> Option<PcmPtr> {
        pcm_hold(
            LIVE_CAP,
            open_pcm(self.cfg.capture_pcm, false, self.rate, self.ch, self.frame),
        )
    }

    /// One read, encode and send. False ends the capture for this call.
    fn step(&mut self) -> bool {
        if self.pcm.is_none() && !self.reopen() {
            return false;
        }
        let Some(p) = self.pcm else {
            return true; // the reopen failed and waited: try again
        };
        let rc = unsafe {
            (self.ta.read)(
                p.0,
                self.pcmbuf.as_mut_ptr() as *mut c_void,
                (self.frame * self.ch * 2) as u32,
            )
        };
        if rc != 0 {
            return self.read_failed(p);
        }
        self.consecutive_err = 0;
        let capms = self
            .clock
            .read(crate::platform::now_ns(libc::CLOCK_MONOTONIC));
        st_umax(|s| &mut s.tot_capms, capms);
        self.count_level();
        for f in 0..CAP_FRAMES_PER_READ as usize {
            self.send_frame(f);
        }
        self.window_log();
        true
    }

    /// The PCM was closed after an error: open it again, giving up after CAP_MAX_ERRORS tries.
    fn reopen(&mut self) -> bool {
        self.pcm = self.open();
        if self.pcm.is_some() {
            return true;
        }
        self.consecutive_err += 1;
        if self.consecutive_err >= CAP_MAX_ERRORS {
            crate::loge!("capture: cannot reopen, giving up for this call");
            return false;
        }
        crate::platform::sleep_ns(100_000_000);
        true
    }

    /// A failed read skips its frames' sequence numbers; from the second error in a row the PCM
    /// is reopened, from the fifth the thread backs off, and at CAP_MAX_ERRORS it gives up.
    fn read_failed(&mut self, p: PcmPtr) -> bool {
        if log_throttle(&mut self.err_log, 2) {
            crate::logw!("capture read failed: {}", pcm_error(p));
        }
        self.consecutive_err += 1;
        if self.consecutive_err >= CAP_MAX_ERRORS {
            crate::loge!(
                "capture: {} consecutive errors, giving up for this call (protecting the audio DSP)",
                self.consecutive_err
            );
            return false;
        }
        st_inc(|s| &mut s.caperr);
        st_inc(|s| &mut s.tot_caperr);
        self.clock.error();
        if self.consecutive_err >= CAP_SLOW_AFTER {
            crate::platform::sleep_ns(100_000_000);
        }
        self.seq = self.seq.wrapping_add(CAP_FRAMES_PER_READ as u16);
        if self.consecutive_err == 1 {
            return true;
        }
        pcm_release(LIVE_CAP, self.pcm);
        crate::platform::sleep_ns(10_000_000);
        self.pcm = self.open();
        true
    }

    /// Down-mix stereo to mono in place, then count the read's peak and voiced frames.
    fn count_level(&mut self) {
        let n = self.frame as usize;
        if self.ch == 2 {
            for i in 0..n {
                self.pcmbuf[i] =
                    ((self.pcmbuf[2 * i] as i32 + self.pcmbuf[2 * i + 1] as i32) / 2) as i16;
            }
        }
        let rpeak = self.pcmbuf[..n]
            .iter()
            .map(|&s| (s as i32).abs())
            .max()
            .unwrap_or(0);
        st_peak(|s| &mut s.peak, rpeak);
        st_peak(|s| &mut s.tot_peak, rpeak);
        st_add(|s| &mut s.tot_cap, CAP_FRAMES_PER_READ);
        if rpeak > VOICED_PEAK {
            st_add(|s| &mut s.tot_voiced, CAP_FRAMES_PER_READ);
        }
    }

    /// Encode frame `f` of the read and send it; a DTX frame (2 bytes or less) only uses a number.
    fn send_frame(&mut self, f: usize) {
        let n = self.frame as usize;
        let start = f * n;
        let len = self.enc.encode(
            &self.pcmbuf[start..start + n],
            self.frame,
            &mut self.pkt[HDR..],
        );
        if len < 0 {
            crate::logw!("opus encode: {}", len);
            return;
        }
        let seq = self.seq;
        self.seq = seq.wrapping_add(1);
        if len <= 2 {
            return;
        }
        let end = HDR + len as usize;
        self.pkt[..HDR].copy_from_slice(&[(seq & 0xff) as u8, (seq >> 8) as u8, 0]);
        client_send(&self.pkt[..end]);
        if self.cfg.loopback {
            jb_put(seq, &self.pkt[HDR..end]);
        }
        st_inc(|s| &mut s.sent);
        st_inc(|s| &mut s.tot_sent);
    }

    /// Every 500 frames (5 s), log the window's counters.
    fn window_log(&mut self) {
        self.frames += CAP_FRAMES_PER_READ;
        if self.frames < 500 {
            return;
        }
        let now = crate::platform::now_ns(libc::CLOCK_MONOTONIC);
        let fps = self.frames as f64 / ((now - self.t0) as f64 / 1e9);
        crate::logi!("{}", crate::protocol::audio_5s_line(&take_window(fps)));
        self.frames = 0;
        self.t0 = now;
    }
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
    let (drate, ich) = inject_format(&cfg, want);
    let Some(dec) = opus::Decoder::new(drate, 1) else {
        return;
    };
    let base = PLAY_PERIOD.load(Ordering::Relaxed);
    let mut pb = Playback {
        cfg,
        want,
        drate,
        ich,
        dframe: drate / 100,
        dec,
        base,
        period: base * drate / 48000,
        periods: 2,
        pcm: None,
        mono: [0i16; 480],
        out: [0i16; 480 * 2],
        pkt: [0u8; MAX_PACKET],
        win_frames: 0,
        slow_windows: 0,
        win_t0: 0,
        werr_log: 0,
    };
    if !pb.first_open() {
        return;
    }
    jb_reset();
    let Some(ta) = ta() else {
        pcm_release(LIVE_PLAY, pb.pcm);
        return;
    };
    pb.win_t0 = crate::platform::now_ns(libc::CLOCK_MONOTONIC);
    while CALL_RUNNING.load(Ordering::Relaxed) {
        if !pb.step(&ta) {
            break;
        }
    }
    pcm_release(LIVE_PLAY, pb.pcm);
}

/// The inject rate and channel count: `want`, refined by what the PCM accepts now, then forced
/// back to 48 kHz / mono if that left the Opus range.
fn inject_format(cfg: &AudioCfg, want: i32) -> (i32, i32) {
    let mut rate = want;
    let mut ch = if cfg.inject_channels == 2 { 2 } else { 1 };
    if let Some((r, c)) = alsa::pcm_negotiate(cfg.card, cfg.inject_pcm, true, want, ch) {
        rate = r;
        ch = c;
    }
    if !opus_rate_ok(rate) {
        rate = DEC_RATE;
    }
    if !(1..=2).contains(&ch) {
        ch = 1;
    }
    (rate, ch)
}

/// The wait before the next try to open a busy inject PCM: 200 ms, then 500 ms, then 1 s.
fn open_retry_ns(tries: u32) -> i64 {
    if tries > 85 {
        1_000_000_000
    } else if tries > 25 {
        500_000_000
    } else {
        200_000_000
    }
}

/// The playback thread's state for one call.
struct Playback {
    cfg: AudioCfg,
    want: i32,
    drate: i32,
    ich: i32,
    dframe: i32,
    dec: opus::Decoder,
    base: i32,
    period: i32,
    periods: u32,
    pcm: Option<PcmPtr>,
    mono: [i16; 480],
    out: [i16; 480 * 2],
    pkt: [u8; MAX_PACKET],
    win_frames: u32,
    slow_windows: u32,
    win_t0: i64,
    werr_log: i64,
}

impl Playback {
    fn open(&self, period: i32, periods: u32) -> Option<PcmPtr> {
        pcm_hold(
            LIVE_PLAY,
            open_pcm_n(
                self.cfg.inject_pcm,
                true,
                self.drate,
                self.ich,
                period,
                periods,
            ),
        )
    }

    /// Open the inject PCM, retrying while the call runs: another stream may still hold it, and
    /// the format it accepts may change once that stream is gone.
    fn first_open(&mut self) -> bool {
        self.pcm = pcm_hold(
            LIVE_PLAY,
            open_pcm(self.cfg.inject_pcm, true, self.drate, self.ich, self.period),
        );
        let mut tries: u32 = 1;
        while self.pcm.is_none() && CALL_RUNNING.load(Ordering::Relaxed) {
            crate::platform::sleep_ns(open_retry_ns(tries));
            self.renegotiate();
            self.pcm = self.open(self.period, 2);
            if self.pcm.is_some() {
                crate::logi!("inject PCM opened after {} tries", tries + 1);
            }
            tries += 1;
        }
        self.pcm.is_some()
    }

    /// Take a new rate or channel count the busy PCM now accepts, with a decoder for it.
    fn renegotiate(&mut self) {
        let Some((r2, c2)) = alsa::pcm_negotiate(
            self.cfg.card,
            self.cfg.inject_pcm,
            true,
            self.want,
            self.ich,
        ) else {
            return;
        };
        let changed = r2 != self.drate || c2 != self.ich;
        if !changed || !opus_rate_ok(r2) || !(1..=2).contains(&c2) {
            return;
        }
        if let Some(d2) = opus::Decoder::new(r2, 1) {
            self.dec = d2;
            self.drate = r2;
            self.ich = c2;
            self.dframe = r2 / 100;
            self.period = self.base * r2 / 48000;
        }
    }

    /// One 10 ms frame. False ends the playback for this call.
    fn step(&mut self, ta: &Ta) -> bool {
        if !self.retune() {
            return false;
        }
        let samples = self.next_frame();
        count_played();
        let Some(p) = self.pcm else {
            return false; // retune already ends the call on a failed reopen
        };
        self.write(ta, p, samples);
        if !self.window_check() {
            return false;
        }
        let stall = STALL_MS.swap(0, Ordering::Relaxed);
        if stall > 0 {
            crate::logw!("debug stall {} ms", stall);
            crate::platform::sleep_ns(stall as i64 * 1_000_000);
        }
        true
    }

    /// TUNE changed the period: reopen with it, falling back to 480-frame periods.
    fn retune(&mut self) -> bool {
        let tuned = PLAY_PERIOD.load(Ordering::Relaxed);
        if self.base == tuned {
            return true;
        }
        pcm_release(LIVE_PLAY, self.pcm);
        self.base = tuned;
        self.period = tuned * self.drate / 48000;
        self.pcm = self.open(self.period, self.periods);
        crate::logi!(
            "inject PCM reopened with {}-frame periods: {}",
            self.period,
            if self.pcm.is_some() { "ok" } else { "FAILED" }
        );
        if self.pcm.is_some() {
            return true;
        }
        PLAY_PERIOD.store(480, Ordering::Relaxed);
        self.base = 480;
        self.period = self.dframe;
        self.pcm = pcm_hold(
            LIVE_PLAY,
            open_pcm(self.cfg.inject_pcm, true, self.drate, self.ich, self.dframe),
        );
        self.pcm.is_some()
    }

    /// Decode the next frame into `mono` (a packet, FEC from the next one, or concealment);
    /// silence when the buffer is empty or the decoder fails. Returns the sample count.
    fn next_frame(&mut self) -> i32 {
        let mut len = 0usize;
        let mut mode = 2i32;
        let have = jb_take(&mut self.pkt, &mut len, &mut mode);
        if CLIENT_UP.load(Ordering::Relaxed) && jb_started() {
            st_inc(|s| &mut s.tot_live);
            if !have {
                st_inc(|s| &mut s.tot_starved);
            }
        }
        let samples = match (have, mode) {
            (false, _) => -1,
            (true, 0) => {
                self.dec
                    .decode(Some(&self.pkt[..len]), &mut self.mono, self.dframe, false)
            }
            // FEC from the next packet
            (true, 1) => self
                .dec
                .decode(Some(&self.pkt[..len]), &mut self.mono, self.dframe, true),
            _ => self.dec.decode(None, &mut self.mono, self.dframe, false), // PLC
        };
        if samples < 0 {
            self.mono = [0i16; 480];
            return self.dframe;
        }
        samples
    }

    /// Write `samples` of `mono` (duplicated for a stereo PCM); a failed write re-prepares it.
    fn write(&mut self, ta: &Ta, p: PcmPtr, samples: i32) {
        let n = samples as usize;
        let rc = if self.ich == 2 {
            for i in 0..n {
                self.out[2 * i] = self.mono[i];
                self.out[2 * i + 1] = self.mono[i];
            }
            unsafe { (ta.write)(p.0, self.out.as_ptr() as *const c_void, (n * 2 * 2) as u32) }
        } else {
            unsafe { (ta.write)(p.0, self.mono.as_ptr() as *const c_void, (n * 2) as u32) }
        };
        if rc == 0 {
            return;
        }
        if log_throttle(&mut self.werr_log, 2) {
            crate::logw!("inject write: {}", pcm_error(p));
        }
        unsafe { (ta.prepare)(p.0) };
        crate::platform::sleep_ns(10_000_000);
    }

    /// Every 100 frames: two windows in a row below 90 frames/s reopen the PCM with one more
    /// period (at most 4). False when that reopen failed.
    fn window_check(&mut self) -> bool {
        self.win_frames += 1;
        if self.win_frames < 100 {
            return true;
        }
        let now = crate::platform::now_ns(libc::CLOCK_MONOTONIC);
        let secs = (now - self.win_t0) as f64 / 1e9;
        self.slow_windows = if secs > 100.0 / 90.0 {
            self.slow_windows + 1
        } else {
            0
        };
        if self.slow_windows >= 2 && !self.reopen_slow(secs) {
            return false;
        }
        self.win_frames = 0;
        self.win_t0 = now;
        true
    }

    fn reopen_slow(&mut self, secs: f64) -> bool {
        self.periods = (self.periods + 1).min(4);
        crate::logw!(
            "inject stream running at {:.0} frames/s: reopening with {} periods",
            100.0 / secs,
            self.periods
        );
        pcm_release(LIVE_PLAY, self.pcm);
        self.pcm = self.open(self.period, self.periods);
        st_inc(|s| &mut s.reopen);
        self.slow_windows = 0;
        self.pcm.is_some()
    }
}

/// Count one played frame and the jitter buffer's depth while it plays.
fn count_played() {
    st_inc(|s| &mut s.played);
    st_inc(|s| &mut s.tot_played);
    let jb = lock(&JB);
    let d = jb_depth(&jb);
    if jb.playing {
        st_umax(|s| &mut s.depth_max, if d < 0 { 0 } else { d as u32 });
    }
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
    crate::logi!(
        "audio tune: prime={} keep={} period={}",
        prime,
        keep,
        period
    );
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
    let s = lock(&STATS);
    crate::protocol::call_totals_line(&crate::protocol::CallTotals {
        cap: s.tot_cap,
        voiced: s.tot_voiced,
        sent: s.tot_sent,
        played: s.tot_played,
        peak: s.tot_peak,
        txdrop: s.tot_txdrop,
        noclient: s.tot_noclient,
        recv: s.tot_recv,
        plc: s.tot_plc,
        starved: s.tot_starved,
        live: s.tot_live,
        capms: s.tot_capms,
        caperr: s.tot_caperr,
    })
}

pub fn call_begin(cfg: AudioCfg) {
    if CALL_RUNNING.load(Ordering::Relaxed) {
        call_end();
    }
    {
        let mut s = lock(&STATS);
        s.tot_cap = 0;
        s.tot_voiced = 0;
        s.tot_sent = 0;
        s.tot_played = 0;
        s.tot_peak = 0;
        s.tot_txdrop = 0;
        s.tot_noclient = 0;
        s.tot_recv = 0;
        s.tot_plc = 0;
        s.tot_starved = 0;
        s.tot_live = 0;
        s.tot_capms = 0;
        s.tot_caperr = 0;
    }
    *lock(&CFG) = cfg;
    jb_reset();
    CALL_RUNNING.store(true, Ordering::Relaxed);
    let cap = std::thread::Builder::new()
        .name("audio-cap".into())
        .spawn(capture_main);
    match cap {
        Ok(h) => *lock(&CAP_HANDLE) = Some(h),
        Err(e) => crate::loge!("capture thread: {}", e),
    }
    let play = std::thread::Builder::new()
        .name("audio-play".into())
        .spawn(playback_main);
    match play {
        Ok(h) => *lock(&PLAY_HANDLE) = Some(h),
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
    let cap = lock(&CAP_HANDLE).take();
    if let Some(h) = cap {
        let _ = h.join();
    }
    let play = lock(&PLAY_HANDLE).take();
    if let Some(h) = play {
        let _ = h.join();
    }
    crate::logi!("audio call end: {}", totals());
}

pub fn shutdown() {
    call_end();
}
