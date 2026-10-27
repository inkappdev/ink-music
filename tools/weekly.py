#!/usr/bin/env python3
"""
ink weekly: this week's songs, picked by hand, put out Friday nights. Reads a public (or unlisted)
YouTube Music playlist the way the app's InnerTube reader does (WEB_REMIX, signed out) and adds the
week to the top of weekly.json at the repo's root, which the app reads from GitHub once a day.

    python3 tools/weekly.py <playlist link or id> ["THE TITLE"] ["a line about it"] [--push]

The title is the playlist's own name unless given ("INK WEEKLY · WEEK 2" says "WEEK 2": the app says
ink weekly above it); the line is the standing one unless given.

Each song keeps its title, artist, record, length, YouTube id and cover: the app plays the
listener's own copy when they have it (files or server), YouTube Music's otherwise. --push commits
weekly.json alone and pushes it (as Bronson <hello@inkhome.app>).
"""
import datetime
import json
import os
import re
import subprocess
import sys
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
FILE = os.environ.get("WEEKLY_FILE") or os.path.join(ROOT, "weekly.json")   # elsewhere to try it
BASE = "https://music.youtube.com"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
TIME = re.compile(r"^\d{1,2}(:\d{2}){1,2}$")
NOTE = "What we're listening to at ink, curated weekly by real people."


def post(body):
    body["context"] = {"client": {"clientName": "WEB_REMIX", "clientVersion": "1.20251020.01.00", "hl": "en", "gl": "US"}}
    req = urllib.request.Request(f"{BASE}/youtubei/v1/browse?prettyPrint=false", json.dumps(body).encode(), {
        "Content-Type": "application/json", "User-Agent": UA, "Origin": BASE, "Referer": BASE + "/",
        "X-YouTube-Client-Name": "67", "X-YouTube-Client-Version": "1.20251020.01.00"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.load(r)


def all_of(x, key, out=None):
    """Every object under `key`, anywhere (not looking inside one found)."""
    out = [] if out is None else out
    if isinstance(x, dict):
        for k, v in x.items():
            if k == key and isinstance(v, dict): out.append(v)
            else: all_of(v, key, out)
    elif isinstance(x, list):
        for v in x: all_of(v, key, out)
    return out


def text(o):
    return (o or {}).get("simpleText") or "".join(r.get("text", "") for r in (o or {}).get("runs", []))


def page_type(run):
    b = ((run.get("navigationEndpoint") or {}).get("browseEndpoint") or {})
    return ((b.get("browseEndpointContextSupportedConfigs") or {}).get("browseEndpointContextMusicConfig") or {}).get("pageType", "")


def biggest(o):
    best = None
    for t in all_thumbs(o):
        if best is None or t.get("width", 0) * t.get("height", 0) > best.get("width", 0) * best.get("height", 0): best = t
    return re.sub(r"=w\d+-h\d+", "=w600-h600", best["url"]) if best else None


def all_thumbs(x):
    if isinstance(x, dict):
        for k, v in x.items():
            if k == "thumbnails" and isinstance(v, list): yield from v
            else: yield from all_thumbs(v)
    elif isinstance(x, list):
        for v in x: yield from all_thumbs(v)


def song(r):
    """A playlist row: its video, title, artists, record, length and cover (InnerTube.track's reading)."""
    if r.get("musicItemRendererDisplayPolicy") == "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT": return None
    cols = [(c.get("musicResponsiveListItemFlexColumnRenderer") or {}).get("text") for c in r.get("flexColumns", [])]
    video = (r.get("playlistItemData") or {}).get("videoId")
    if not video or not cols: return None
    title = text(cols[0])
    artists, album, seconds = [], "", 0
    for run in [run for c in cols[1:] for run in (c or {}).get("runs", [])]:
        t, kind = run.get("text", ""), page_type(run)
        if kind == "MUSIC_PAGE_TYPE_ALBUM": album = t
        elif "ARTIST" in kind or kind == "MUSIC_PAGE_TYPE_USER_CHANNEL": artists.append(t)
    if not artists:
        first = ((cols[1] or {}).get("runs") or [{}])[0].get("text", "").strip() if len(cols) > 1 else ""
        if first and not TIME.match(first): artists = [first]
    for c in r.get("fixedColumns", []):
        t = text((c.get("musicResponsiveListItemFixedColumnRenderer") or {}).get("text")).strip()
        if TIME.match(t): seconds = sum(int(p) * 60 ** i for i, p in enumerate(reversed(t.split(":"))))
    return {"title": title, "artist": ", ".join(artists) or "unknown", "album": album, "seconds": seconds,
            "video": video, "art": biggest(r.get("thumbnail"))}


def playlist(link):
    pid = re.search(r"list=([A-Za-z0-9_-]+)", link)
    pid = pid.group(1) if pid else link.strip()
    page, songs = post({"browseId": "VL" + pid}), []
    name = next((text(h.get("title")) for k in ("musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer",
                 "musicEditablePlaylistDetailHeaderRenderer") for h in all_of(page, k) if text(h.get("title"))), "")
    for _ in range(10):
        songs += [s for s in map(song, all_of(page, "musicResponsiveListItemRenderer")) if s]
        token = next((c.get("continuationEndpoint", {}).get("continuationCommand", {}).get("token")
                      for c in all_of(page, "continuationItemRenderer") if c.get("continuationEndpoint")), None)
        if not token: break
        page = post({"continuation": token})
    seen, out = set(), []
    for s in songs:
        if s["video"] not in seen: seen.add(s["video"]); out.append(s)
    return pid, name, out


def main():
    args = [a for a in sys.argv[1:] if a != "--push"]
    if len(args) < 1: sys.exit(__doc__)
    link = args[0]
    pid, name, songs = playlist(link)
    # the playlist's name, less the "ink weekly" the app already says
    title = args[1] if len(args) > 1 else re.sub(r"^\s*ink\s+weekly\s*[·:|-]?\s*", "", name, flags=re.I) or name
    note = args[2] if len(args) > 2 else NOTE
    if not songs: sys.exit("no songs read: is the playlist public (or unlisted)?")
    data = json.load(open(FILE)) if os.path.exists(FILE) else {"weeks": []}
    weeks = [w for w in data["weeks"] if w.get("playlist") != pid]   # the same playlist again: its week replaced
    number = (max((w["week"] for w in weeks), default=0) + 1) if len(weeks) == len(data["weeks"]) else \
        next(w["week"] for w in data["weeks"] if w.get("playlist") == pid)
    week = {"week": number, "date": datetime.date.today().isoformat(), "title": title, "note": note, "playlist": pid, "songs": songs}
    data["weeks"] = sorted([week] + weeks, key=lambda w: -w["week"])
    with open(FILE, "w") as f: json.dump(data, f, indent=1, ensure_ascii=False); f.write("\n")
    print(f"week {number}: {title}, {len(songs)} songs")
    for i, s in enumerate(songs, 1): print(f"  {i:2}. {s['artist']} - {s['title']}")
    if "--push" in sys.argv:
        git = ["git", "-C", ROOT, "-c", "user.name=Bronson", "-c", "user.email=hello@inkhome.app"]
        subprocess.run(git + ["add", "weekly.json"], check=True)
        subprocess.run(git + ["commit", "-q", "-m", f"ink weekly {number}: {title}", "--", "weekly.json"], check=True)
        subprocess.run(["git", "-C", ROOT, "push", "-q", "origin", "main"], check=True)
        print("pushed: the app has it within a day (at once on its next open after a few hours)")


if __name__ == "__main__":
    main()
