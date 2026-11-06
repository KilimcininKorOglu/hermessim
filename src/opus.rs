// Minimal libopus FFI used by the audio pump. GPL-3.0-or-later; libopus itself is BSD.
#![cfg(target_os = "android")]

use libc::c_int;

pub const APPLICATION_VOIP: c_int = 2048;
pub const SIGNAL_VOICE: c_int = 3001;
pub const SET_BITRATE: c_int = 4002;
pub const SET_INBAND_FEC: c_int = 4012;
pub const SET_PACKET_LOSS_PERC: c_int = 4014;
pub const SET_SIGNAL: c_int = 4024;

#[repr(C)]
pub struct OpusEncoder {
    _p: [u8; 0],
}

#[repr(C)]
pub struct OpusDecoder {
    _p: [u8; 0],
}

extern "C" {
    fn opus_encoder_create(
        fs: c_int,
        channels: c_int,
        application: c_int,
        error: *mut c_int,
    ) -> *mut OpusEncoder;
    fn opus_encoder_ctl(st: *mut OpusEncoder, request: c_int, ...) -> c_int;
    fn opus_encode(
        st: *mut OpusEncoder,
        pcm: *const i16,
        frame_size: c_int,
        data: *mut u8,
        max_data_bytes: c_int,
    ) -> c_int;
    fn opus_encoder_destroy(st: *mut OpusEncoder);

    fn opus_decoder_create(fs: c_int, channels: c_int, error: *mut c_int) -> *mut OpusDecoder;
    fn opus_decode(
        st: *mut OpusDecoder,
        data: *const u8,
        len: c_int,
        pcm: *mut i16,
        frame_size: c_int,
        decode_fec: c_int,
    ) -> c_int;
    fn opus_decoder_destroy(st: *mut OpusDecoder);
}

pub struct Encoder {
    ptr: *mut OpusEncoder,
}

impl Encoder {
    pub fn new(rate: i32, channels: i32, application: i32) -> Option<Self> {
        let mut err = 0;
        let ptr = unsafe { opus_encoder_create(rate, channels, application, &mut err) };
        if ptr.is_null() {
            crate::loge!("opus encoder: {}", err);
            None
        } else {
            Some(Self { ptr })
        }
    }

    pub fn ctl(&self, request: i32, value: i32) {
        unsafe {
            opus_encoder_ctl(self.ptr, request, value);
        }
    }

    pub fn encode(&mut self, pcm: &[i16], frame: i32, out: &mut [u8]) -> i32 {
        unsafe {
            opus_encode(
                self.ptr,
                pcm.as_ptr(),
                frame,
                out.as_mut_ptr(),
                out.len() as c_int,
            )
        }
    }
}

impl Drop for Encoder {
    fn drop(&mut self) {
        unsafe { opus_encoder_destroy(self.ptr) };
    }
}

pub struct Decoder {
    ptr: *mut OpusDecoder,
}

impl Decoder {
    pub fn new(rate: i32, channels: i32) -> Option<Self> {
        let mut err = 0;
        let ptr = unsafe { opus_decoder_create(rate, channels, &mut err) };
        if ptr.is_null() {
            crate::loge!("opus decoder: {}", err);
            None
        } else {
            Some(Self { ptr })
        }
    }

    pub fn decode(&mut self, data: Option<&[u8]>, pcm: &mut [i16], fec: bool) -> i32 {
        let (ptr, len) = match data {
            Some(d) => (d.as_ptr(), d.len() as c_int),
            None => (std::ptr::null(), 0),
        };
        unsafe {
            opus_decode(
                self.ptr,
                ptr,
                len,
                pcm.as_mut_ptr(),
                pcm.len() as c_int,
                fec as c_int,
            )
        }
    }
}

impl Drop for Decoder {
    fn drop(&mut self) {
        unsafe { opus_decoder_destroy(self.ptr) };
    }
}
