# SIM Relay host module installer hook (sourced by Magisk). GPL-3.0-or-later.
# The daemon for this phone's Android (Magisk's ARCH, from ro.product.cpu.abi): 64-bit, or 32-bit (cheap phones, old
# ones on custom ROMs). The module carries both; only this one stays.
case "$ARCH" in
  arm64) ABI=arm64-v8a ;;
  arm) ABI=armeabi-v7a ;;
  *) abort "! unsupported processor: $ARCH (TosbiSim needs an ARM phone)" ;;
esac
mv -f "$MODPATH/bin/$ABI/simrelayd" "$MODPATH/simrelayd" || abort "! the module has no daemon for $ABI"
rm -rf "$MODPATH/bin"
set_perm "$MODPATH/simrelayd" 0 0 0755
# Installed from inside the app (one APK for everyone): the module carries no APK, so take the installed one.
PRIV="$MODPATH/system/priv-app/SimRelayHost"
if [ ! -f "$PRIV/SimRelayHost.apk" ]; then
  APK=$(pm path dev.simrelay.host 2>/dev/null | head -1 | sed 's/^package://')
  [ -f "$APK" ] || abort "! Install the TosbiSim Host app first, then install this module from inside it"
  mkdir -p "$PRIV" && cp "$APK" "$PRIV/SimRelayHost.apk" || abort "! could not copy the app"
  set_perm_recursive "$PRIV" 0 0 0755 0644
  ui_print "- using the installed TosbiSim Host app ($APK)"
fi
ui_print "- TosbiSim Host is installed."
ui_print "- Restart the phone: TosbiSim starts by itself and opens"
ui_print "  its screen, ready to pair with your iPhone."
# Android keeps a parsed copy of system apps in /data/system/package_cache and, for files on the system
# partition, only invalidates it on an OTA (mtime is ignored). Drop ours so an updated APK is re-read.
rm -f /data/system/package_cache/*/SimRelayHost-*
