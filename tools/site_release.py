#!/usr/bin/env python3
"""A release on inkhome.app, where both apps' updaters look first (GitHub's account suspended 2026-11-01; the site
can't be). Takes what tools/release.sh left in build/release/ and puts it in ../inkhome-site/public/download/:

  - each kind of build (the phone's apk, the mac's dmg, windows' msi, linux's deb and rpm) replaces that kind's older
    one; a kind not in this release stays as it was (a phone-only release leaves the computer's builds be);
  - one over Pages' 25 MiB a file is kept in 20 MiB parts (parts/, gitignored), sent whole by
    functions/download/[name].js, whose BUILDS this rewrites;
  - the source of this commit beside them (ink-music-<v>-source.tar.xz), as the GPL asks;
  - SHA256SUMS for everything there, and latest.json: GitHub's release answer in shape (tag_name, body, assets), so
    the updaters read it as they read GitHub's. Its assets are this version's only: a computer offered 2.1.2 must
    never be handed 2.1.1's dmg (it would offer itself for ever), so a phone-only release offers computers nothing;
  - the music page's download links moved on to the new files.

Nothing is deployed or committed: that's the site's own deploy (its README), a separate, deliberate step.
    python3 tools/site_release.py [--site ../inkhome-site]
"""
import argparse, hashlib, json, pathlib, re, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PART = 20 * 1024 * 1024
MAX = 25 * 1024 * 1024
BASE = "https://inkhome.app/download/"
# a build's kind, from its name: ink-music-2.1.2.apk, ink-music-2.1.2-mac.dmg, -windows.msi, -linux.deb / .rpm
KINDS = {".apk": ".apk", "-mac.dmg": "-mac.dmg", "-windows.msi": "-windows.msi", "-linux.deb": "-linux.deb",
         "-linux.rpm": "-linux.rpm", "-source.tar.xz": "-source.tar.xz"}
NAME = re.compile(r"^ink-music-(\d+(?:\.\d+)*)(.*)$")


def kind(name):
    m = NAME.match(name)
    return (m.group(1), m.group(2)) if m and m.group(2) in KINDS else (None, None)


def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--site", default=str(ROOT.parent / "inkhome-site"))
    site = pathlib.Path(a.parse_args().site)
    down, parts = site / "public/download", site / "public/download/parts"
    parts.mkdir(parents=True, exist_ok=True)
    gradle = (ROOT / "app/build.gradle.kts").read_text()
    version = re.search(r'versionName = "(.*)"', gradle).group(1)
    code = re.search(r"versionCode = (\d+)", gradle).group(1)
    notes = (ROOT / f"fastlane/metadata/android/en-US/changelogs/{code}.txt").read_text().strip()

    built = {kind(p.name)[1]: p for p in (ROOT / "build/release").glob("ink-music-*") if kind(p.name)[0] == version}
    if ".apk" not in built:
        sys.exit(f"no ink-music-{version}.apk in build/release: run tools/release.sh first")
    # the source of what was built: this commit's tracked files (nothing uncommitted, nothing ignored)
    if subprocess.run(["git", "-C", str(ROOT), "status", "--porcelain", "--untracked-files=no"], capture_output=True, text=True).stdout.strip():
        sys.exit("uncommitted changes: commit them, so the source beside the build is what was built")
    src = ROOT / f"build/release/ink-music-{version}-source.tar.xz"
    # less the README's and the store's pictures (30 MB of them), as 2.1.1's had: the code and what builds it
    tar = subprocess.run(["git", "-C", str(ROOT), "archive", f"--prefix=ink-music-{version}/", "HEAD", "--", ".",
                          ":(exclude).github/*.gif", ":(exclude).github/*.png", ":(exclude).github/*.jpg", ":(exclude).github/shots",
                          ":(exclude)fastlane/metadata/android/en-US/images"], capture_output=True, check=True).stdout
    src.write_bytes(subprocess.run(["xz", "-9", "-T0"], input=tar, capture_output=True, check=True).stdout)
    built["-source.tar.xz"] = src

    # what's there now, by kind: whole files, and parted ones (name.00, name.01...)
    there = {}
    for p in down.iterdir():
        v, k = kind(p.name)
        if k: there.setdefault(k, set()).add(p.name)
    for p in parts.iterdir():
        v, k = kind(p.name.rsplit(".", 1)[0])
        if k: there.setdefault(k, set()).add(p.name.rsplit(".", 1)[0])
    sums = {}
    old = down / "SHA256SUMS"
    if old.exists():
        for line in old.read_text().splitlines():
            if line.strip(): h, n = line.split(None, 1); sums[n.strip()] = h

    for k, p in built.items():
        for name in there.get(k, ()):  # the older one of this kind goes
            (down / name).unlink(missing_ok=True)
            for q in parts.glob(name + ".*"): q.unlink()
            sums.pop(name, None)
        data = p.read_bytes()
        if len(data) > MAX:
            for i in range(0, len(data), PART):
                (parts / f"{p.name}.{i // PART:02d}").write_bytes(data[i:i + PART])
        else:
            (down / p.name).write_bytes(data)
        sums[p.name] = sha(p)

    # each parted build's sizes, for the function that sends it whole
    builds = {}
    for q in sorted(parts.iterdir()):
        builds.setdefault(q.name.rsplit(".", 1)[0], []).append(q.stat().st_size)
    fn = site / "functions/download/[name].js"
    js = fn.read_text()
    js = re.sub(r"const BUILDS = \{.*?\n\};", "const BUILDS = " + json.dumps(builds, indent=2) + ";", js, count=1, flags=re.S)
    fn.write_text(js)

    def size(name):
        return (down / name).stat().st_size if (down / name).exists() else sum(builds.get(name, []))

    names = sorted(sums)
    old.write_text("".join(f"{sums[n]}  {n}\n" for n in names))
    latest = {
        "tag_name": f"v{version}",
        "name": f"ink music {version}",
        "body": notes,
        "assets": [{"name": n, "browser_download_url": BASE + n, "size": size(n)} for n in names if kind(n)[0] == version]
        + [{"name": "SHA256SUMS", "browser_download_url": BASE + "SHA256SUMS", "size": old.stat().st_size}],
    }
    (down / "latest.json").write_text(json.dumps(latest, indent=2) + "\n")

    # the music page's links, each kind to the file now there
    page = site / "public/music/index.html"
    html = page.read_text()
    for n in names:
        v, k = kind(n)
        html = re.sub(r"/download/ink-music-\d+(?:\.\d+)*" + re.escape(k) + '"', f"/download/{n}\"", html)
        if k == ".apk":
            html = re.sub(r"android, \d+(?:\.\d+)*<", f"android, {v}<", html)
    page.write_text(html)

    for n in names:
        print(f"  {n}  {size(n) / 1e6:.1f} MB" + ("  (this release)" if kind(n)[0] == version else ""))
    print(f"latest.json: v{version}, {len(latest['assets']) - 1} builds offered. Deploy the site to put it out.")


if __name__ == "__main__":
    main()
