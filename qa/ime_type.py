"""Type a string on the Fire TV IME grid using D-pad moves.
Grid (row, col): row0 = 1..0, row1 = a..j, row2 = k..t, row3 = u,v,w,x,y,z,!,,,.,@
Row 4 has wide keys: [aA][#$%][acute][Space][Delete][Clear]; Space sits under y/z, Clear under ./@.
Assumes the IME cursor sits on a known key (default 'a'). Usage: ime_type.py "text" [start] [--clear]
"""
import subprocess, sys, time
rows = ["1234567890", "abcdefghij", "klmnopqrst", "uvwxyz!,.@"]
pos = {ch: (r, c) for r, line in enumerate(rows) for c, ch in enumerate(line)}
def key(k, n=1):
    for _ in range(n):
        subprocess.run(["adb", "shell", "input", "dpad", "keyevent", k], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(0.45)
def move(cur, target):
    dr = target[0] - cur[0]; dc = target[1] - cur[1]
    key("KEYCODE_DPAD_DOWN" if dr > 0 else "KEYCODE_DPAD_UP", abs(dr))
    key("KEYCODE_DPAD_RIGHT" if dc > 0 else "KEYCODE_DPAD_LEFT", abs(dc))
    return target
def resync_to_u():
    # From any row-4 key: UP lands somewhere on row 3; LEFT x9 pins the cursor on 'u'.
    key("KEYCODE_DPAD_UP"); key("KEYCODE_DPAD_LEFT", 9)
    return pos['u']
def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    text = args[0]
    cur = pos[args[1]] if len(args) > 1 else pos['a']
    if "--clear" in sys.argv:
        cur = move(cur, pos['@']); key("KEYCODE_DPAD_DOWN"); key("KEYCODE_DPAD_CENTER"); cur = resync_to_u()
    for ch in text.lower():
        if ch == ' ':
            cur = move(cur, pos['y']); key("KEYCODE_DPAD_DOWN"); key("KEYCODE_DPAD_CENTER"); cur = resync_to_u()
            continue
        cur = move(cur, pos[ch]); key("KEYCODE_DPAD_CENTER")
    print("typed", text)
main()
