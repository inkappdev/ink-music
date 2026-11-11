#!/usr/bin/env python3
"""
Automix's blends as they were heard, measured: where the two songs' kicks really landed against each other.

The phone keeps each blend's two songs apart while it mixes them (Blend: touch files/blends/on in the app's external
files, Android/data/app.ink.music/files/blends; "<time>-<new id>-<rate>-<channels>-parts.pcm", 16 bit, left the new
song, right the old one). This pulls them, and for each: both songs' bass (under 150 Hz) energy each 10 ms, its rises,
and the lag between the two (within 150 ms) where those rises line up best, and how clearly (the best against the next
best lag a beat's tenth away). A kick drum a little off its partner is a flam: over ~25 ms it's heard.

It also reads the phone's log for each blend's plan ("plan: <old> into <new>: kicks N ms apart as planned ..."), so
the planner's guess sits beside what happened.

    python3 tools/blends.py on        # start keeping blends on the phone
    python3 tools/blends.py           # pull and measure
    python3 tools/blends.py off       # stop

Needs numpy (a venv: python3 -m venv /tmp/v && /tmp/v/bin/pip install numpy).
"""
import os
import re
import subprocess
import sys

DIR = "/sdcard/Android/data/app.ink.music/files/blends"
HERE = os.path.join(os.environ.get("TMPDIR", "/tmp"), "ink-blends")


def adb(*a, text=True):
    return subprocess.run(["adb", *a], capture_output=True, text=text).stdout


def measure(path, rate):
    import numpy as np
    x = np.fromfile(path, dtype="<i2").astype(np.float64) / 32768
    if len(x) < 2 * rate:
        return None
    new, old = x[0::2], x[1::2]
    hop = rate // 100

    def low(s):
        f = np.fft.rfft(s)
        f[int(150 * len(s) / rate):] = 0
        return np.fft.irfft(f, len(s))

    def rises(s):
        e = (low(s)[: len(s) // hop * hop] ** 2).reshape(-1, hop).sum(1)
        r = np.maximum(np.diff(e, prepend=e[0]), 0)
        return r * r, e

    rn, en = rises(new)
    ro, eo = rises(old)
    # where both sound: the blend's middle (each over a tenth of its loudest)
    both = (en > en.max() * .1) & (eo > eo.max() * .1)
    if both.sum() < 100:
        return {"note": "one side has no bass where they overlap"}
    rn = np.where(both, rn, 0); ro = np.where(both, ro, 0)
    lags = range(-15, 16)
    score = []
    for L in lags:
        a = rn[max(0, -L): len(rn) - max(0, L)]
        b = ro[max(0, L): len(ro) - max(0, -L)]
        score.append(float((a * b).sum()))
    score = np.array(score)
    best = int(np.argmax(score))
    away = [s for i, s in enumerate(score) if abs(i - best) >= 3]
    clear = score[best] / (max(away) + 1e-12) if away else 0
    return {"apart": (best - 15) * 10, "clear": clear, "kicks": f"new {rn.sum() ** .5:.2g}, old {ro.sum() ** .5:.2g}"}


def main():
    if sys.argv[1:] == ["on"]:
        adb("shell", f"mkdir -p {DIR} && touch {DIR}/on"); print("keeping blends"); return
    if sys.argv[1:] == ["off"]:
        adb("shell", f"rm -f {DIR}/on"); print("stopped"); return
    os.makedirs(HERE, exist_ok=True)
    names = [n for n in adb("shell", f"ls {DIR}").split() if n.endswith("-parts.pcm")]
    for n in names:
        if not os.path.exists(os.path.join(HERE, n)):
            adb("pull", f"{DIR}/{n}", HERE)
    plans = {}
    # the phone's log, or one kept as it ran (BLENDS_LOG: adb logcat -s ink-crossfade:D > file), its own may have rolled on
    log = open(os.environ["BLENDS_LOG"]).read() if os.environ.get("BLENDS_LOG") else adb("logcat", "-d", "-s", "ink-crossfade:D")
    for line in log.splitlines():
        m = re.search(r"plan: (\S+) into (\S+): kicks (-?\d+) ms apart as planned \(sure ([\d.]+)[^)]*\)(.*)", line)
        if m:
            plans[m.group(2)] = f"planned {m.group(3)} ms (sure {m.group(4)}){', moved' if 'moved' in m.group(5) else ''}"
    print(f"{'blend (new song)':<34} {'heard apart':>11} {'clear':>6}  plan")
    for n in sorted(names):
        m = re.match(r"(\d+)-(.+)-(\d+)-(\d+)-parts\.pcm", n)
        if not m:
            continue
        r = measure(os.path.join(HERE, n), int(m.group(3)))
        if r is None:
            continue
        if "note" in r:
            print(f"{m.group(1)}-{m.group(2):<23} {r['note']}"); continue
        flam = "  FLAM" if abs(r["apart"]) >= 30 and r["clear"] >= 1.3 else ""
        print(f"{m.group(1)}-{m.group(2):<23} {r['apart']:>8} ms {r['clear']:>6.2f}  {plans.get(m.group(2), '-')}{flam}")


if __name__ == "__main__":
    main()
