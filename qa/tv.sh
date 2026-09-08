#!/bin/sh
# Tiny Fire TV test helper: qa/tv.sh k KEY [KEY...] | s name | t "text" | launch | home | log
D=/Users/marc/Desktop/TV/qa/shots
case "$1" in
  # D-pad source, like the real Fire TV remote (a keyboard source puts the Fire TV IME in hardware-keyboard mode).
  k) shift; for key in "$@"; do adb shell input dpad keyevent "$key" >/dev/null 2>&1; sleep 0.9; done ;;
  # Type into the Fire TV keyboard grid: navigate to each letter from the top-left ("1") position.
  ime) shift; python3 /Users/marc/Desktop/TV/qa/ime_type.py "$@" ;;
  tree) adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml | python3 -c "
import re,sys
x=sys.stdin.read()
for n in re.findall(r'<node [^>]*>', x):
    t=re.search(r'text=\"([^\"]*)\"',n); b=re.search(r'bounds=\"([^\"]*)\"',n)
    fo='focused=\"true\"' in n
    if (t and t.group(1)) or fo: print(('F ' if fo else '  ')+(t.group(1) if t else '')[:44].ljust(46), b.group(1) if b else '')
" | head -${2:-40} ;;
  s) adb exec-out screencap -p > "$D/$2.png"; echo "shot $2" ;;
  t) adb shell input text "$(printf %s "$2" | sed 's/ /%s/g')" >/dev/null 2>&1; sleep 0.5 ;;
  launch) adb shell am start -n com.iptv.tv/.MainActivity >/dev/null 2>&1; sleep "${2:-6}" ;;
  home) adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1 ;;
  top) adb shell dumpsys activity activities 2>/dev/null | grep -E "mResumedActivity" | head -1 ;;
  log) adb logcat -d -v time 2>/dev/null | grep -E "IptvTv|FATAL|AndroidRuntime: |Fatal signal" | grep -v "Using default boot image\|lock profiling" | tail -${2:-15} ;;
  clear) adb logcat -c ;;
  # tab N: from any page go to top-bar tab N (Home=0 Live=1 Movies=2 Series=3 Web=4 Guide=5 Multi=6 Recordings=7) and open it.
  tab) shift; n=$1; keys="DPAD_UP DPAD_UP DPAD_UP"; for i in 1 2 3 4 5 6 7 8 9; do keys="$keys DPAD_LEFT"; done; for i in $(seq 1 $((n+2))); do keys="$keys DPAD_RIGHT"; done; for key in $keys; do adb shell input dpad keyevent $key >/dev/null 2>&1; sleep 0.5; done; adb shell input dpad keyevent DPAD_CENTER >/dev/null 2>&1; sleep "${2:-5}" ;;
esac
