# HermesSIM host module installer hook (sourced by Magisk). GPL-3.0-or-later.
# The daemon for this phone's Android (Magisk's ARCH, from ro.product.cpu.abi): 64-bit, or 32-bit (cheap phones, old
# ones on custom ROMs). The module carries both; only this one stays.
case "$ARCH" in
  arm64) ABI=arm64-v8a ;;
  arm) ABI=armeabi-v7a ;;
  *) abort "! unsupported processor: $ARCH (HermesSIM needs an ARM phone)" ;;
esac
mv -f "$MODPATH/bin/$ABI/hermessimd" "$MODPATH/hermessimd" || abort "! the module has no daemon for $ABI"
rm -rf "$MODPATH/bin"
set_perm "$MODPATH/hermessimd" 0 0 0755
# Installed from inside the app (one APK for everyone): the module carries no APK, so take the installed one.
PRIV="$MODPATH/system/priv-app/HermesSimHost"
if [ ! -f "$PRIV/HermesSimHost.apk" ]; then
  APK=$(pm path com.keremgok.hermessim.host 2>/dev/null | head -1 | sed 's/^package://')
  [ -f "$APK" ] || abort "! Install the HermesSIM Host app first, then install this module from inside it"
  mkdir -p "$PRIV" && cp "$APK" "$PRIV/HermesSimHost.apk" || abort "! could not copy the app"
  set_perm_recursive "$PRIV" 0 0 0755 0644
  ui_print "- using the installed HermesSIM Host app ($APK)"
fi
ui_print "- HermesSIM Host is installed."
ui_print "- Restart the phone: HermesSIM starts by itself and opens"
ui_print "  its screen, ready to pair with your phone."
# Android keeps a parsed copy of system apps in /data/system/package_cache and, for files on the system
# partition, only invalidates it on an OTA (mtime is ignored). Drop ours so an updated APK is re-read.
rm -f /data/system/package_cache/*/HermesSimHost-*
