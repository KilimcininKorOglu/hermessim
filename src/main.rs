// hermessimd-rs — HermesSIM host daemon, Rust reimplementation. GPL-3.0-or-later.

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

fn main() {
    println!("hermessimd-rs scaffold");
}
