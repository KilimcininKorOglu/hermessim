// Kernel ALSA control/PCM ABI used by the daemon. GPL-3.0-or-later.
//
// The layouts below were checked against the NDK's sound/asound.h with clang's record layout
// dump for both aarch64 and armv7 (see the const size assertions at the bottom).
#![cfg(target_os = "android")]

use std::mem::size_of;
use std::os::unix::io::RawFd;

use libc::{c_long, c_ulong};

pub const ELEM_ID_NAME_MAXLEN: usize = 44;
pub const ELEM_IFACE_MIXER: u32 = 2;
pub const ELEM_TYPE_BOOLEAN: u32 = 1;
pub const ELEM_TYPE_INTEGER: u32 = 2;
pub const ELEM_TYPE_ENUMERATED: u32 = 3;
pub const ELEM_TYPE_INTEGER64: u32 = 6;

pub const EVENT_ELEM: i32 = 0;
pub const EVENT_MASK_VALUE: u32 = 1 << 0;
pub const EVENT_MASK_REMOVE: u32 = u32::MAX;

pub const PCM_PARAM_ACCESS: usize = 0;
pub const PCM_PARAM_SUBFORMAT: usize = 2;
pub const PCM_PARAM_FIRST_MASK: usize = PCM_PARAM_ACCESS;
pub const PCM_PARAM_LAST_MASK: usize = PCM_PARAM_SUBFORMAT;
pub const PCM_PARAM_SAMPLE_BITS: usize = 8;
pub const PCM_PARAM_CHANNELS: usize = 10;
pub const PCM_PARAM_RATE: usize = 11;
pub const PCM_PARAM_TICK_TIME: usize = 19;
pub const PCM_PARAM_FIRST_INTERVAL: usize = PCM_PARAM_SAMPLE_BITS;
pub const PCM_PARAM_LAST_INTERVAL: usize = PCM_PARAM_TICK_TIME;

#[repr(C)]
#[derive(Copy, Clone)]
pub struct ElemId {
    pub numid: u32,
    pub iface: u32,
    pub device: u32,
    pub subdevice: u32,
    pub name: [u8; ELEM_ID_NAME_MAXLEN],
    pub index: u32,
}

impl ElemId {
    pub fn zeroed() -> Self {
        unsafe { std::mem::zeroed() }
    }
}

#[repr(C)]
pub struct ElemList {
    pub offset: u32,
    pub space: u32,
    pub used: u32,
    pub count: u32,
    pub pids: *mut ElemId,
    pub reserved: [u8; 50],
}

#[repr(C)]
#[derive(Copy, Clone)]
pub struct IntInfo {
    pub min: c_long,
    pub max: c_long,
    pub step: c_long,
}
#[repr(C)]
#[derive(Copy, Clone)]
pub struct Int64Info {
    pub min: i64,
    pub max: i64,
    pub step: i64,
}
#[repr(C)]
#[derive(Copy, Clone)]
pub struct EnumInfo {
    pub items: u32,
    pub item: u32,
    pub name: [u8; 64],
    pub names_ptr: u64,
    pub names_length: u32,
}
#[repr(C)]
#[derive(Copy, Clone)]
pub union ElemInfoValue {
    pub integer: IntInfo,
    pub integer64: Int64Info,
    pub enumerated: EnumInfo,
    pub reserved: [u8; 128],
}

#[repr(C)]
#[derive(Copy, Clone)]
pub struct ElemInfo {
    pub id: ElemId,
    pub type_: u32,
    pub access: u32,
    pub count: u32,
    pub owner: i32,
    pub value: ElemInfoValue,
    pub reserved: [u8; 64],
}

#[repr(C)]
#[derive(Copy, Clone)]
pub union ElemValueUnion {
    pub integer: [c_long; 128],
    pub integer64: [i64; 64],
    pub enumerated: [u32; 128],
    pub bytes: [u8; 512],
}

#[repr(C)]
#[derive(Copy, Clone)]
pub struct ElemValue {
    pub id: ElemId,
    pub indirect: u32,
    pub _pad: u32,
    pub value: ElemValueUnion,
    pub reserved: [u8; 128],
}

#[repr(C)]
#[derive(Copy, Clone)]
pub struct ElemEventElem {
    pub mask: u32,
    pub id: ElemId,
}
#[repr(C)]
#[derive(Copy, Clone)]
pub union ElemEventData {
    pub elem: ElemEventElem,
    pub data8: [u8; 60],
}
#[repr(C)]
#[derive(Copy, Clone)]
pub struct ElemEvent {
    pub type_: i32,
    pub data: ElemEventData,
}

#[repr(C)]
#[derive(Copy, Clone)]
pub struct SndMask {
    pub bits: [u32; 8],
}
#[repr(C)]
#[derive(Copy, Clone)]
pub struct SndInterval {
    pub min: u32,
    pub max: u32,
    /// openmin:1 openmax:1 integer:1 empty:1
    pub flags: u32,
}
#[repr(C)]
#[derive(Copy, Clone)]
pub struct PcmHwParams {
    pub flags: u32,
    pub masks: [SndMask; PCM_PARAM_LAST_MASK - PCM_PARAM_FIRST_MASK + 1],
    pub mres: [SndMask; 5],
    pub intervals: [SndInterval; PCM_PARAM_LAST_INTERVAL - PCM_PARAM_FIRST_INTERVAL + 1],
    pub ires: [SndInterval; 9],
    pub rmask: u32,
    pub cmask: u32,
    pub info: u32,
    pub msbits: u32,
    pub rate_num: u32,
    pub rate_den: u32,
    pub fifo_size: c_ulong,
    pub reserved: [u8; 64],
}

impl PcmHwParams {
    pub fn full() -> Self {
        let mut p: Self = unsafe { std::mem::zeroed() };
        for m in p.masks.iter_mut() {
            m.bits = [u32::MAX; 8];
        }
        for i in p.intervals.iter_mut() {
            i.min = 0;
            i.max = u32::MAX;
            i.flags = 0;
        }
        p.rmask = u32::MAX;
        p
    }
}

const IOC_NRBITS: u32 = 8;
const IOC_TYPEBITS: u32 = 8;
const IOC_SIZEBITS: u32 = 14;
const IOC_NRSHIFT: u32 = 0;
const IOC_TYPESHIFT: u32 = IOC_NRSHIFT + IOC_NRBITS;
const IOC_SIZESHIFT: u32 = IOC_TYPESHIFT + IOC_TYPEBITS;
const IOC_DIRSHIFT: u32 = IOC_SIZESHIFT + IOC_SIZEBITS;
const IOC_WRITE: u32 = 1;
const IOC_READ: u32 = 2;

pub const fn ioc(dir: u32, ty: u32, nr: u32, size: usize) -> c_ulong {
    ((dir << IOC_DIRSHIFT) | ((size as u32) << IOC_SIZESHIFT) | (ty << IOC_TYPESHIFT) | nr) as c_ulong
}

pub const CTL_ELEM_LIST: c_ulong = ioc(IOC_READ | IOC_WRITE, b'U' as u32, 0x10, size_of::<ElemList>());
pub const CTL_ELEM_INFO: c_ulong = ioc(IOC_READ | IOC_WRITE, b'U' as u32, 0x11, size_of::<ElemInfo>());
pub const CTL_ELEM_READ: c_ulong = ioc(IOC_READ | IOC_WRITE, b'U' as u32, 0x12, size_of::<ElemValue>());
pub const CTL_ELEM_WRITE: c_ulong = ioc(IOC_READ | IOC_WRITE, b'U' as u32, 0x13, size_of::<ElemValue>());
pub const CTL_SUBSCRIBE_EVENTS: c_ulong = ioc(IOC_READ | IOC_WRITE, b'U' as u32, 0x16, size_of::<i32>());
pub const PCM_HW_REFINE: c_ulong = ioc(IOC_READ | IOC_WRITE, b'A' as u32, 0x10, size_of::<PcmHwParams>());

pub const fn eviocgname(len: usize) -> c_ulong {
    ioc(IOC_READ, b'E' as u32, 0x06, len)
}
pub const fn eviocgbit(ev: u32, len: usize) -> c_ulong {
    ioc(IOC_READ, b'E' as u32, 0x20 + ev, len)
}
pub const EVIOCGRAB: c_ulong = ioc(IOC_WRITE, b'E' as u32, 0x90, size_of::<i32>());

pub fn ioctl_ptr<T>(fd: RawFd, request: c_ulong, value: &mut T) -> i32 {
    unsafe { libc::ioctl(fd, request as libc::c_int, value as *mut T) }
}

pub fn open_ctl(path: &str) -> Option<RawFd> {
    let c = std::ffi::CString::new(path).ok()?;
    let fd = unsafe {
        libc::open(
            c.as_ptr(),
            libc::O_RDWR | libc::O_NONBLOCK | libc::O_CLOEXEC,
        )
    };
    (fd >= 0).then_some(fd)
}

/// SNDRV_CTL_IOCTL_ELEM_INFO by control name.
pub fn ctl_info(fd: RawFd, name: &str) -> Option<ElemInfo> {
    if fd < 0 || name.is_empty() {
        return None;
    }
    let mut info: ElemInfo = unsafe { std::mem::zeroed() };
    info.id.iface = ELEM_IFACE_MIXER;
    info.id.name = crate::platform::name_bytes(name);
    (ioctl_ptr(fd, CTL_ELEM_INFO, &mut info) == 0).then_some(info)
}

pub fn ctl_info_numid(fd: RawFd, numid: u32) -> Option<ElemInfo> {
    let mut info: ElemInfo = unsafe { std::mem::zeroed() };
    info.id.numid = numid;
    (ioctl_ptr(fd, CTL_ELEM_INFO, &mut info) == 0).then_some(info)
}

pub fn ctl_read(fd: RawFd, numid: u32) -> Option<ElemValue> {
    let mut v: ElemValue = unsafe { std::mem::zeroed() };
    v.id.numid = numid;
    (ioctl_ptr(fd, CTL_ELEM_READ, &mut v) == 0).then_some(v)
}

pub fn ctl_write(fd: RawFd, value: &mut ElemValue) -> bool {
    ioctl_ptr(fd, CTL_ELEM_WRITE, value) == 0
}

pub fn subscribe_events(fd: RawFd) -> bool {
    let mut on: i32 = 1;
    ioctl_ptr(fd, CTL_SUBSCRIBE_EVENTS, &mut on) == 0
}

pub fn elem_integer(v: &ElemValue, i: usize) -> c_long {
    unsafe { v.value.integer[i] }
}
pub fn elem_enum(v: &ElemValue, i: usize) -> u32 {
    unsafe { v.value.enumerated[i] }
}
pub fn set_elem_integer(v: &mut ElemValue, i: usize, value: c_long) {
    unsafe { v.value.integer[i] = value };
}
pub fn set_elem_enum(v: &mut ElemValue, i: usize, value: u32) {
    unsafe { v.value.enumerated[i] = value };
}

pub fn info_integer(info: &ElemInfo) -> (i64, i64) {
    unsafe { (info.value.integer.min as i64, info.value.integer.max as i64) }
}
pub fn info_enum_items(info: &ElemInfo) -> u32 {
    unsafe { info.value.enumerated.items }
}

/// The C daemon's value_ok: only boolean, integer and enum controls are written; n <= count;
/// integers inside [min,max] unless max <= min; enum items < items.
pub fn value_ok(name: &str, info: &ElemInfo, values: &[i64], n: usize) -> bool {
    if n < 1 || n as u32 > info.count {
        crate::loge!("'{}': {} value(s) for a {}-value control: not written", name, n, info.count);
        return false;
    }
    for &v in values.iter().take(n) {
        let ok = match info.type_ {
            ELEM_TYPE_BOOLEAN => v == 0 || v == 1,
            ELEM_TYPE_INTEGER => {
                let (min, max) = info_integer(info);
                max <= min || (v >= min && v <= max)
            }
            ELEM_TYPE_ENUMERATED => v >= 0 && v < info_enum_items(info) as i64,
            other => {
                crate::loge!("'{}': control type {} is never written", name, other);
                return false;
            }
        };
        if !ok {
            crate::loge!("'{}': value {} is outside the control's range: not written", name, v);
            return false;
        }
    }
    true
}

/// Enum item names joined with '|', cut at a whole item. The C daemon reads one ELEM_INFO per
/// index (the names are not all present in the first reply on old kernels).
pub fn enum_items(fd: RawFd, info: &ElemInfo, max_len: usize) -> String {
    if max_len == 0 {
        return String::new();
    }
    let mut out = String::new();
    let items = info_enum_items(info).min(64);
    for k in 0..items {
        let mut e = *info;
        e.value.enumerated.item = k;
        if ioctl_ptr(fd, CTL_ELEM_INFO, &mut e) != 0 {
            continue;
        }
        let name = crate::platform::kernel_name(unsafe { &e.value.enumerated.name });
        let sep = if out.is_empty() { 0 } else { 1 };
        if out.len() + sep + name.len() >= max_len {
            break;
        }
        if sep == 1 {
            out.push('|');
        }
        out.push_str(&name);
    }
    out
}

/// The PCM accepts these parameters right now (HW_REFINE on the device node). Returns the refined
/// rate/channels, or None when the node is busy/absent.
pub fn pcm_negotiate(
    card: i32,
    device: i32,
    playback: bool,
    want_rate: i32,
    want_ch: i32,
) -> Option<(i32, i32)> {
    let path = format!(
        "/dev/snd/pcmC{}D{}{}",
        card,
        device,
        if playback { 'p' } else { 'c' }
    );
    let c = std::ffi::CString::new(path).ok()?;
    let fd = unsafe { libc::open(c.as_ptr(), libc::O_RDWR | libc::O_NONBLOCK | libc::O_CLOEXEC) };
    if fd < 0 {
        return None;
    }
    let mut p = PcmHwParams::full();
    let ok = ioctl_ptr(fd, PCM_HW_REFINE, &mut p) == 0;
    unsafe { libc::close(fd) };
    if !ok {
        return None;
    }
    let mut rate = want_rate;
    let mut ch = want_ch;
    let ri = &p.intervals[PCM_PARAM_RATE - PCM_PARAM_FIRST_INTERVAL];
    if (want_rate as u32) < ri.min || (want_rate as u32) > ri.max {
        let opus = [48000, 16000, 24000, 12000, 8000];
        let mut best = 0;
        for &r in &opus {
            if (r as u32) < ri.min || (r as u32) > ri.max {
                continue;
            }
            if best == 0 || (r - want_rate).abs() < (best - want_rate).abs() {
                best = r;
            }
        }
        if best != 0 {
            rate = best;
            crate::logw!(
                "pcm {} {}: profile rate {} not accepted now ({}..{} Hz): using {}",
                device,
                if playback { "out" } else { "in" },
                want_rate,
                ri.min,
                ri.max,
                best
            );
        }
    }
    let ci = &p.intervals[PCM_PARAM_CHANNELS - PCM_PARAM_FIRST_INTERVAL];
    if (want_ch as u32) < ci.min {
        ch = ci.min as i32;
    } else if (want_ch as u32) > ci.max {
        ch = ci.max as i32;
    }
    if ch > 2 {
        ch = 2;
    }
    if ch != want_ch {
        crate::logw!(
            "pcm {} {}: {} channel(s) not accepted: using {}",
            device,
            if playback { "out" } else { "in" },
            want_ch,
            ch
        );
    }
    Some((rate, ch))
}

const _: () = {
    assert!(size_of::<ElemId>() == 64);
    assert!(size_of::<ElemInfo>() == 272);
    assert!(size_of::<ElemEvent>() == 72);
    assert!(size_of::<SndMask>() == 32);
    assert!(size_of::<SndInterval>() == 12);
    if size_of::<usize>() == 8 {
        assert!(size_of::<ElemList>() == 80);
        assert!(size_of::<ElemValue>() == 1224);
        assert!(size_of::<PcmHwParams>() == 608);
    } else {
        assert!(size_of::<ElemList>() == 72);
        assert!(size_of::<ElemValue>() == 712);
        assert!(size_of::<PcmHwParams>() == 604);
    }
};
