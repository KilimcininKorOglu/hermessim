// Builds the vendored libopus 1.5.2 for the Android target. The daemon loads the phone's own
// libtinyalsa at runtime, so no other native dependency is linked.
use std::env;
use std::path::{Path, PathBuf};

fn latest_dir(parent: &Path) -> Option<PathBuf> {
    let mut entries: Vec<PathBuf> = std::fs::read_dir(parent)
        .ok()?
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| p.is_dir())
        .collect();
    entries.sort();
    entries.pop()
}

fn find_ndk() -> PathBuf {
    if let Ok(p) = env::var("ANDROID_NDK_HOME") {
        return PathBuf::from(p);
    }
    if let Ok(p) = env::var("ANDROID_NDK_ROOT") {
        return PathBuf::from(p);
    }
    if let Some(home) = env::var_os("HOME") {
        let sdk = Path::new(&home).join("Library/Android/sdk/ndk");
        if let Some(p) = latest_dir(&sdk) {
            return p;
        }
    }
    if let Some(p) = latest_dir(Path::new("/opt/homebrew/share/android-commandlinetools/ndk")) {
        return p;
    }
    panic!("Android NDK not found: set ANDROID_NDK_HOME (the Rust daemon builds libopus with it)");
}

fn main() {
    println!("cargo:rerun-if-changed=vendor/opus");
    println!("cargo:rerun-if-env-changed=ANDROID_NDK_HOME");
    println!("cargo:rerun-if-env-changed=ANDROID_NDK_ROOT");

    if env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("android") {
        return; // host-side unit tests do not need the codec
    }

    let arch = env::var("CARGO_CFG_TARGET_ARCH").unwrap_or_default();
    let abi = match arch.as_str() {
        "aarch64" => "arm64-v8a",
        "arm" => "armeabi-v7a",
        other => panic!("unsupported Android architecture for opus: {other}"),
    };

    let ndk = find_ndk();
    let toolchain = ndk.join("build/cmake/android.toolchain.cmake");
    assert!(toolchain.is_file(), "NDK toolchain missing: {}", toolchain.display());
    let bin = latest_dir(&ndk.join("toolchains/llvm/prebuilt"))
        .expect("NDK clang toolchain missing")
        .join("bin");
    let clang_prefix = match arch.as_str() {
        "aarch64" => "aarch64-linux-android29",
        "arm" => "armv7a-linux-androideabi29",
        other => panic!("unsupported Android architecture for opus: {other}"),
    };
    let clang = bin.join(format!("{clang_prefix}-clang"));
    let clangxx = bin.join(format!("{clang_prefix}-clang++"));
    assert!(clang.is_file(), "NDK clang missing: {}", clang.display());
    let mut c_cfg = cc::Build::new();
    c_cfg.compiler(&clang);
    let mut cxx_cfg = cc::Build::new();
    cxx_cfg.compiler(&clangxx);

    let dst = cmake::Config::new("vendor/opus")
        .init_c_cfg(c_cfg)
        .init_cxx_cfg(cxx_cfg)
        .define("OPUS_BUILD_PROGRAMS", "OFF")
        .define("OPUS_BUILD_TESTING", "OFF")
        .define("OPUS_BUILD_SHARED_LIBRARY", "OFF")
        .define("OPUS_INSTALL_PKG_CONFIG_MODULE", "OFF")
        .define("OPUS_INSTALL_CMAKE_CONFIG_MODULE", "OFF")
        .define("CMAKE_TOOLCHAIN_FILE", &toolchain)
        .define("ANDROID_ABI", abi)
        .define("ANDROID_PLATFORM", "29")
        .define("CMAKE_BUILD_TYPE", "Release")
        .build();

    println!("cargo:rustc-link-search=native={}/lib", dst.display());
    println!("cargo:rustc-link-lib=static=opus");
    println!("cargo:rustc-link-lib=log");
    println!("cargo:rustc-link-lib=dl");
    println!("cargo:rustc-link-lib=m");
}
