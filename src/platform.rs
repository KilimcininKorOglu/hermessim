// Android platform helpers: logcat, small file helpers, signal-safe process spawning. GPL-3.0-or-later.
#![cfg(target_os = "android")]

use std::ffi::CString;
use std::io;

use libc::{c_char, c_int};

extern "C" {
    fn __android_log_print(prio: c_int, tag: *const c_char, fmt: *const c_char, ...) -> c_int;
}

const TAG: &[u8] = b"hermessimd\0";
pub const LOG_INFO: c_int = 4;
pub const LOG_WARN: c_int = 5;
pub const LOG_ERROR: c_int = 6;

pub fn log(prio: c_int, msg: &str) {
    let clean = msg.replace('\0', "?");
    if let Ok(c) = CString::new(clean) {
        unsafe {
            __android_log_print(
                prio,
                TAG.as_ptr() as *const c_char,
                c"%s".as_ptr(),
                c.as_ptr(),
            );
        }
    }
}

#[macro_export]
macro_rules! logi {
    ($($arg:tt)*) => { $crate::platform::log($crate::platform::LOG_INFO, &format!($($arg)*)) };
}
#[macro_export]
macro_rules! logw {
    ($($arg:tt)*) => { $crate::platform::log($crate::platform::LOG_WARN, &format!($($arg)*)) };
}
#[macro_export]
macro_rules! loge {
    ($($arg:tt)*) => { $crate::platform::log($crate::platform::LOG_ERROR, &format!($($arg)*)) };
}

/// The C library's own text for an errno, as the C daemon's strerror() logs it (no " (os error N)" suffix).
pub fn strerror(errno: c_int) -> String {
    let mut buf = [0 as c_char; 128];
    let rc = unsafe { libc::strerror_r(errno, buf.as_mut_ptr(), buf.len()) };
    if rc != 0 {
        return format!("Unknown error {}", errno);
    }
    unsafe { std::ffi::CStr::from_ptr(buf.as_ptr()) }
        .to_string_lossy()
        .into_owned()
}

/// An io::Error as the C daemon words it: strerror for an OS error, the error's own text otherwise.
pub fn err_text(e: &io::Error) -> String {
    match e.raw_os_error() {
        Some(errno) => strerror(errno),
        None => e.to_string(),
    }
}

/// strerror(errno) for the last failed call.
pub fn errstr() -> String {
    err_text(&io::Error::last_os_error())
}

/// A NUL-terminated kernel name (snd_ctl_elem_id.name) as a String.
pub fn kernel_name(bytes: &[u8]) -> String {
    let end = bytes.iter().position(|&b| b == 0).unwrap_or(bytes.len());
    String::from_utf8_lossy(&bytes[..end]).into_owned()
}

pub fn name_bytes(name: &str) -> [u8; 44] {
    let mut out = [0u8; 44];
    let b = name.as_bytes();
    let n = b.len().min(out.len() - 1);
    out[..n].copy_from_slice(&b[..n]);
    out
}

pub fn now_ns(clock: libc::clockid_t) -> i64 {
    let mut t: libc::timespec = unsafe { std::mem::zeroed() };
    unsafe { libc::clock_gettime(clock, &mut t) };
    t.tv_sec as i64 * 1_000_000_000 + t.tv_nsec as i64
}

pub fn now_sec() -> i64 {
    now_ns(libc::CLOCK_MONOTONIC) / 1_000_000_000
}

/// sockaddr_un for an abstract-namespace unix socket, with the length bind/connect needs.
pub fn abstract_addr(name: &[u8]) -> (libc::sockaddr_un, libc::socklen_t) {
    let mut a: libc::sockaddr_un = unsafe { std::mem::zeroed() };
    a.sun_family = libc::AF_UNIX as libc::sa_family_t;
    let path = &mut a.sun_path;
    for (i, &b) in name.iter().enumerate() {
        if i + 1 < path.len() {
            path[i + 1] = b as libc::c_char;
        }
    }
    let len = std::mem::offset_of!(libc::sockaddr_un, sun_path) + 1 + name.len();
    (a, len as libc::socklen_t)
}

pub fn bind_abstract(name: &[u8], sock_type: i32, backlog: i32) -> io::Result<libc::c_int> {
    let fd = unsafe { libc::socket(libc::AF_UNIX, sock_type | libc::SOCK_CLOEXEC, 0) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    let (addr, len) = abstract_addr(name);
    let rc = unsafe {
        libc::bind(
            fd,
            &addr as *const libc::sockaddr_un as *const libc::sockaddr,
            len,
        )
    };
    if rc < 0 {
        let err = io::Error::last_os_error();
        unsafe { libc::close(fd) };
        return Err(err);
    }
    let listens = sock_type == libc::SOCK_STREAM || sock_type == libc::SOCK_SEQPACKET;
    if listens && unsafe { libc::listen(fd, backlog) } < 0 {
        let err = io::Error::last_os_error();
        unsafe { libc::close(fd) };
        return Err(err);
    }
    Ok(fd)
}

pub fn connect_abstract(name: &[u8]) -> io::Result<libc::c_int> {
    let fd = unsafe { libc::socket(libc::AF_UNIX, libc::SOCK_STREAM | libc::SOCK_CLOEXEC, 0) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    let (addr, len) = abstract_addr(name);
    let rc = unsafe {
        libc::connect(
            fd,
            &addr as *const libc::sockaddr_un as *const libc::sockaddr,
            len,
        )
    };
    if rc < 0 {
        let err = io::Error::last_os_error();
        unsafe { libc::close(fd) };
        return Err(err);
    }
    Ok(fd)
}

pub fn boot_seconds() -> i64 {
    now_ns(libc::CLOCK_BOOTTIME) / 1_000_000_000
}

pub fn sleep_ns(ns: i64) {
    if ns <= 0 {
        return;
    }
    let req = libc::timespec {
        tv_sec: (ns / 1_000_000_000) as libc::time_t,
        tv_nsec: (ns % 1_000_000_000) as libc::c_long,
    };
    unsafe {
        libc::nanosleep(&req, std::ptr::null_mut());
    }
}

// Bionic <spawn.h> (API 28+); the libc crate has no Android bindings for it.
#[allow(non_camel_case_types)]
type posix_spawnattr_t = *mut libc::c_void;
const POSIX_SPAWN_SETSIGDEF: libc::c_short = 4;
const POSIX_SPAWN_SETSIGMASK: libc::c_short = 8;

extern "C" {
    static environ: *const *mut c_char;
    fn posix_spawn(
        pid: *mut libc::pid_t,
        path: *const c_char,
        actions: *const libc::c_void,
        attr: *const posix_spawnattr_t,
        argv: *const *mut c_char,
        envp: *const *mut c_char,
    ) -> c_int;
    fn posix_spawnattr_init(attr: *mut posix_spawnattr_t) -> c_int;
    fn posix_spawnattr_destroy(attr: *mut posix_spawnattr_t) -> c_int;
    fn posix_spawnattr_setflags(attr: *mut posix_spawnattr_t, flags: libc::c_short) -> c_int;
    fn posix_spawnattr_setsigmask(
        attr: *mut posix_spawnattr_t,
        mask: *const libc::sigset_t,
    ) -> c_int;
    fn posix_spawnattr_setsigdefault(
        attr: *mut posix_spawnattr_t,
        mask: *const libc::sigset_t,
    ) -> c_int;
}

/// Fire a child with no signal blocked and SIGPIPE at its default, with posix_spawn like the C
/// daemon: the daemon blocks SIGTERM and co. outside its ppoll and ignores SIGPIPE, and a child
/// would inherit both. argv[0] is an absolute path (no PATH search). The main loop reaps the
/// child with waitpid(-1, WNOHANG). The error is posix_spawn's own errno.
pub fn spawn(argv: &[&str]) -> io::Result<libc::pid_t> {
    if argv.is_empty() || argv[0].is_empty() {
        return Err(io::Error::from_raw_os_error(libc::EINVAL));
    }
    let owned = argv
        .iter()
        .map(|a| CString::new(*a).map_err(|_| io::Error::from_raw_os_error(libc::EINVAL)))
        .collect::<io::Result<Vec<CString>>>()?;
    let mut ptrs: Vec<*mut c_char> = owned.iter().map(|c| c.as_ptr() as *mut c_char).collect();
    ptrs.push(std::ptr::null_mut());
    let mut pid: libc::pid_t = 0;
    let rc = unsafe {
        let mut at: posix_spawnattr_t = std::ptr::null_mut();
        let mut none: libc::sigset_t = std::mem::zeroed();
        let mut def: libc::sigset_t = std::mem::zeroed();
        libc::sigemptyset(&mut none);
        libc::sigemptyset(&mut def);
        libc::sigaddset(&mut def, libc::SIGPIPE);
        posix_spawnattr_init(&mut at);
        posix_spawnattr_setsigmask(&mut at, &none);
        posix_spawnattr_setsigdefault(&mut at, &def);
        posix_spawnattr_setflags(&mut at, POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETSIGDEF);
        let rc = posix_spawn(
            &mut pid,
            ptrs[0],
            std::ptr::null(),
            &at,
            ptrs.as_ptr(),
            environ,
        );
        posix_spawnattr_destroy(&mut at);
        rc
    };
    if rc != 0 {
        return Err(io::Error::from_raw_os_error(rc));
    }
    Ok(pid)
}
