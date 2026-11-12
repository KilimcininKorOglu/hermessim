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
    fn opus_packet_get_nb_samples(data: *const u8, len: c_int, fs: c_int) -> c_int;
}

/// The samples one packet holds at `rate`, read from its TOC; negative for a malformed packet.
pub fn packet_samples(data: &[u8], rate: i32) -> i32 {
    if data.is_empty() {
        return -1;
    }
    unsafe { opus_packet_get_nb_samples(data.as_ptr(), data.len() as c_int, rate) }
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

    /// Decodes at most `frame_size` samples into `pcm`. Concealment (no data) and FEC produce exactly
    /// `frame_size` samples, so it must be one 10 ms frame at the decoder's rate, not the buffer's length.
    pub fn decode(
        &mut self,
        data: Option<&[u8]>,
        pcm: &mut [i16],
        frame_size: i32,
        fec: bool,
    ) -> i32 {
        let (ptr, len) = match data {
            Some(d) => (d.as_ptr(), d.len() as c_int),
            None => (std::ptr::null(), 0),
        };
        let frame_size = frame_size.clamp(0, pcm.len() as i32);
        unsafe {
            opus_decode(
                self.ptr,
                ptr,
                len,
                pcm.as_mut_ptr(),
                frame_size as c_int,
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
