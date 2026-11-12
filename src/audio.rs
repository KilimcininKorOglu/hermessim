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
use crate::playout::{self, Playout, Put, Take};

const SOCK_NAME: &[u8] = b"hermessimd-audio";
const CAP_RATE: i32 = 16000;
const CAP_FRAME: i32 = 160;
const CAP_PERIODS: u32 = 4;
const CAP_READ: i32 = CAP_FRAME;
const CAP_FRAMES_PER_READ: u32 = (CAP_READ / CAP_FRAME) as u32;
const CAP_MAX_ERRORS: u32 = 600;
const CAP_SLOW_AFTER: u32 = 5;
const DEC_RATE: i32 = 48000;
const DEC_FRAME: i32 = 480; // 10 ms at 48 kHz
const MAX_PACKET: usize = playout::MAX_PACKET;
const MAX_OPUS: usize = 250; // an encoded packet's ceiling (protocol README "Call audio")
const HDR: usize = 2; // [u16 tick]
const BITRATE: i32 = 24000;
const LOSS_DEFAULT: i32 = 10; // expected loss until the phone's AudioReport says otherwise (LOSS)
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
    let handle = paths
        .iter()
        .filter_map(|path| CString::new(path.as_str()).ok())
        .map(|c| unsafe { libc::dlopen(c.as_ptr(), libc::RTLD_NOW) })
        .find(|h| !h.is_null());
    let Some(handle) = handle else {
        crate::loge!("dlopen libtinyalsa: {}", dl_error());
        return None;
    };
    unsafe { ta_symbols(handle) }
}

/// dlerror()'s text, or "" when it has none.
fn dl_error() -> String {
    unsafe {
        let p = libc::dlerror();
        if p.is_null() {
            String::new()
        } else {
            CStr::from_ptr(p).to_string_lossy().into_owned()
        }
    }
}

/// The tinyalsa functions from an opened library; pcm_stop is optional.
unsafe fn ta_symbols(handle: *mut c_void) -> Option<Ta> {
    unsafe {
        let open = symbol(handle, b"pcm_open\0");
        let is_ready = symbol(handle, b"pcm_is_ready\0");
        let get_error = symbol(handle, b"pcm_get_error\0");
        let read = symbol(handle, b"pcm_read\0");
        let write = symbol(handle, b"pcm_write\0");
        let close = symbol(handle, b"pcm_close\0");
        let prepare = symbol(handle, b"pcm_prepare\0");
        let stop = symbol(handle, b"pcm_stop\0");
        let required = [open, is_ready, get_error, read, write, close, prepare];
        if required.iter().any(|p| p.is_null()) {
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

// ------------------------------------------------------------------ playout

static JB: Mutex<Playout> = Mutex::new(Playout::new());

static PLAY_PERIOD: AtomicI32 = AtomicI32::new(480);
static STALL_MS: AtomicI32 = AtomicI32::new(0);
static CALL_RUNNING: AtomicBool = AtomicBool::new(false);
/// Set by the host app (FRAME, LOSS) when the call's audio changes path or the phone reports its loss.
static FRAME_TICKS: AtomicI32 = AtomicI32::new(1);
static LOSS: AtomicI32 = AtomicI32::new(LOSS_DEFAULT);

fn jb_reset() {
    lock(&JB).reset();
}

/// One packet from the client (or the loopback): its length comes from the Opus TOC.
fn jb_put(tick: u16, data: &[u8]) {
    let samples = opus::packet_samples(data, DEC_RATE);
    if samples < DEC_FRAME || samples % DEC_FRAME != 0 {
        return; // not a whole number of 10 ms steps
    }
    let r = lock(&JB).put(tick, samples / DEC_FRAME, data);
    if matches!(r, Put::Late | Put::Resync) {
        st_inc(|s| &mut s.late);
    }
    if r == Put::Resync {
        crate::logw!("audio playout follows the sender from tick {}", tick);
    }
    if matches!(r, Put::Ok | Put::Resync) {
        st_inc(|s| &mut s.recv);
        st_inc(|s| &mut s.tot_recv);
    }
}

/// The playout saw this client's first packet: the per-call loss totals start there.
fn jb_started() -> bool {
    lock(&JB).started
}

/// One 10 ms tick from the playout; FEC, concealment and trimming are counted here.
fn jb_take(buf: &mut [u8; MAX_PACKET]) -> Take {
    let (take, depth, dropped) = {
        let mut jb = lock(&JB);
        let before = jb.dropped;
        let take = jb.take(buf);
        (take, jb.depth(), jb.dropped - before)
    };
    if take == Take::Silence {
        return take;
    }
    st_jb_depth(depth);
    if dropped > 0 {
        st_add(|s| &mut s.catchup, dropped);
    }
    match take {
        Take::Fec { .. } => st_inc(|s| &mut s.fec),
        Take::Plc => {
            st_inc(|s| &mut s.plc);
            st_inc(|s| &mut s.tot_plc);
        }
        _ => {}
    }
    take
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
    let (prime, keep) = {
        let jb = lock(&JB);
        (jb.prime, jb.keep)
    };
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
        prime,
        keep,
        period: PLAY_PERIOD.load(Ordering::Relaxed),
        frame_ms: FRAME_TICKS.load(Ordering::Relaxed) * 10,
        loss: LOSS.load(Ordering::Relaxed),
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
    let Some(ls) = audio_listen() else {
        return;
    };
    let mut buf = [0u8; HDR + MAX_PACKET];
    let mut fd: RawFd = -1;
    while let Some(pf) = io_wait(ls, fd) {
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

/// Bind the audio socket @hermessimd-audio (SEQPACKET, backlog 1).
fn audio_listen() -> Option<RawFd> {
    match crate::platform::bind_abstract(SOCK_NAME, libc::SOCK_SEQPACKET, 1) {
        Ok(fd) => {
            crate::logi!(
                "audio socket @{} listening",
                String::from_utf8_lossy(SOCK_NAME)
            );
            Some(fd)
        }
        Err(e) => {
            crate::loge!(
                "audio socket @{}: {}",
                String::from_utf8_lossy(SOCK_NAME),
                crate::platform::err_text(&e)
            );
            None
        }
    }
}

/// Wait for the listener and the client (when there is one). An interrupted poll returns no
/// events; None is a poll error, which ends the io thread.
fn io_wait(ls: RawFd, fd: RawFd) -> Option<[libc::pollfd; 2]> {
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
    if n >= 0 {
        return Some(pf);
    }
    if std::io::Error::last_os_error().raw_os_error() != Some(libc::EINTR) {
        return None;
    }
    pf[0].revents = 0;
    pf[1].revents = 0;
    Some(pf)
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
        gathered: [0i16; 480 * 2],
        have: 0,
        loss: LOSS_DEFAULT,
        pkt: [0u8; HDR + MAX_OPUS],
        tick: 0,
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
    enc.ctl(opus::SET_PACKET_LOSS_PERC, LOSS_DEFAULT);
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
    /// Up to two 10 ms reads for one 20 ms packet.
    gathered: [i16; 480 * 2],
    have: usize,
    /// The expected loss the encoder has now.
    loss: i32,
    pkt: [u8; HDR + MAX_OPUS],
    tick: u16,
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
        self.gather();
        self.window_log();
        true
    }

    /// Keeps the read for its packet; a whole packet (10 or 20 ms, FRAME) is encoded and sent.
    fn gather(&mut self) {
        let n = self.frame as usize;
        let start = self.have * n;
        self.gathered[start..start + n].copy_from_slice(&self.pcmbuf[..n]);
        self.have += 1;
        let want = if FRAME_TICKS.load(Ordering::Relaxed) == 2 { 2 } else { 1 };
        if self.have < want {
            return;
        }
        self.follow_loss();
        self.send_packet();
        self.have = 0;
    }

    /// The expected loss the phone's last report asked for (LOSS), set on the encoder when it changed.
    fn follow_loss(&mut self) {
        let want = LOSS.load(Ordering::Relaxed);
        if want != self.loss {
            self.enc.ctl(opus::SET_PACKET_LOSS_PERC, want);
            self.loss = want;
        }
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
        // That 10 ms (and what was gathered for its packet) is lost; the tick goes on so the receiver conceals it.
        self.tick = self
            .tick
            .wrapping_add((self.have as u32 + CAP_FRAMES_PER_READ) as u16);
        self.have = 0;
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

    /// Encode the gathered reads as one packet and send it; an error or a DTX packet (2 bytes or less) only uses
    /// its ticks.
    fn send_packet(&mut self) {
        let samples = self.have * self.frame as usize;
        let len = self.enc.encode(
            &self.gathered[..samples],
            samples as i32,
            &mut self.pkt[HDR..],
        );
        if len < 0 {
            crate::logw!("opus encode: {}", len);
        }
        let tick = self.tick;
        self.tick = tick.wrapping_add(self.have as u16);
        if len <= 2 {
            return;
        }
        let end = HDR + len as usize;
        self.pkt[..HDR].copy_from_slice(&tick.to_le_bytes());
        client_send(&self.pkt[..end]);
        if self.cfg.loopback {
            jb_put(tick, &self.pkt[HDR..end]);
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
        held: [0i16; 480 * playout::MAX_TICKS as usize],
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
    /// A decoded packet's ticks: the later ones wait here for their Carry takes.
    held: [i16; 480 * playout::MAX_TICKS as usize],
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

    /// The next tick into `mono`: a packet decoded whole, FEC over a gap (Opus conceals the part FEC does not
    /// cover), concealment, the next tick of the last decode, or silence while the playout waits. Whatever a decode
    /// did not fill is silence. Returns the sample count (one tick).
    fn next_frame(&mut self) -> i32 {
        let take = jb_take(&mut self.pkt);
        if CLIENT_UP.load(Ordering::Relaxed) && jb_started() {
            st_inc(|s| &mut s.tot_live);
            if take == Take::Silence {
                st_inc(|s| &mut s.tot_starved);
            }
        }
        let d = self.dframe;
        let n = d as usize;
        let (got, want) = match take {
            Take::Play { len, ticks, .. } => {
                let all = self.held.len() as i32;
                (self.dec.decode(Some(&self.pkt[..len]), &mut self.held, all, false), d * ticks)
            }
            Take::Fec { len, gap, .. } => {
                (self.dec.decode(Some(&self.pkt[..len]), &mut self.held, d * gap, true), d * gap)
            }
            Take::Plc => (self.dec.decode(None, &mut self.held, d, false), d),
            Take::Carry { index } => {
                let at = index as usize * n;
                self.mono[..n].copy_from_slice(&self.held[at..at + n]);
                return d;
            }
            Take::Silence => {
                self.mono = [0i16; 480];
                return d;
            }
        };
        let got = got.max(0) as usize;
        if got < want as usize {
            self.held[got..want as usize].fill(0);
        }
        self.mono[..n].copy_from_slice(&self.held[..n]);
        d
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

/// Count one played tick and the playout's depth while it plays.
fn count_played() {
    st_inc(|s| &mut s.played);
    st_inc(|s| &mut s.tot_played);
    let (d, playing) = {
        let jb = lock(&JB);
        (jb.depth(), jb.playing)
    };
    if playing {
        st_umax(|s| &mut s.depth_max, d.max(0) as u32);
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
    {
        let mut jb = lock(&JB);
        jb.prime = prime;
        jb.keep = keep;
    }
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
    let (prime, keep) = {
        let jb = lock(&JB);
        (jb.prime, jb.keep)
    };
    format!(
        "prime={} keep={} period={}",
        prime,
        keep,
        PLAY_PERIOD.load(Ordering::Relaxed)
    )
}

/// FRAME: the length of the packets sent to the client, 10 (Bluetooth) or 20 ms (internet). False for another value.
pub fn frame(ms: i32) -> bool {
    let Some(ticks) = crate::protocol::frame_ticks(ms) else {
        return false;
    };
    if FRAME_TICKS.swap(ticks, Ordering::Relaxed) != ticks {
        crate::logi!("audio frame: {} ms", ms);
    }
    true
}

/// LOSS: the encoder's expected loss in percent (0..100), from the phone's AudioReport.
pub fn loss(percent: i32) -> bool {
    crate::protocol::loss_percent(percent)
        .map(|p| LOSS.store(p, Ordering::Relaxed))
        .is_some()
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
    LOSS.store(LOSS_DEFAULT, Ordering::Relaxed); // a new call starts without the phone's report
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
