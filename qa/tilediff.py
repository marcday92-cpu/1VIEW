"""Is a screen region changing? Pull two raw screencaps `gap` seconds apart and report the
fraction of sampled pixels that differ inside rect x0 y0 x1 y1 (device pixels)."""
import subprocess, sys, time, struct
def grab():
    raw = subprocess.run(["adb", "exec-out", "screencap"], stdout=subprocess.PIPE).stdout
    w, h, fmt = struct.unpack_from("<III", raw, 0)
    hdr = 16 if len(raw) >= 16 + w * h * 4 else 12
    return w, h, raw[hdr:]
x0, y0, x1, y1 = map(int, sys.argv[1:5]); gap = float(sys.argv[5]) if len(sys.argv) > 5 else 2.0
w, h, a = grab(); time.sleep(gap); _, _, b = grab()
diff = total = 0
for y in range(y0, y1, 8):
    for x in range(x0, x1, 8):
        i = (y * w + x) * 4
        total += 1
        if a[i:i+3] != b[i:i+3]: diff += 1
print(f"region {x0},{y0}-{x1},{y1}: {diff}/{total} sampled pixels changed ({100*diff/total:.1f}%)")
