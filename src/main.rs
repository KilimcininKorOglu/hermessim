// hermessimd-rs — HermesSIM host daemon, Rust reimplementation. GPL-3.0-or-later.

#[cfg(target_os = "android")]
#[macro_use]
mod platform;
#[cfg(target_os = "android")]
mod alsa;
mod protocol;

fn main() {
    println!("hermessimd-rs scaffold");
}
