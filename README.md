<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="112" alt="ink music" />
</p>

<h1 align="center">ink music</h1>

<p align="center">
  A free, open-source music player for Android.<br />
  Pure black, warm white type, no chrome: the album art is the only color on screen.
</p>

<p align="center">
  <a href="https://github.com/inkappdev/ink-music/releases/latest"><img src="https://img.shields.io/github/v/release/inkappdev/ink-music?style=flat-square&color=000000&label=release" alt="Latest release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-000000?style=flat-square" alt="GPL-3.0" /></a>
  <img src="https://img.shields.io/badge/android-12%2B-000000?style=flat-square" alt="Android 12+" />
  <img src="https://img.shields.io/badge/ads%20%26%20tracking-none-000000?style=flat-square" alt="No ads, no tracking" />
</p>

<p align="center">
  <a href="https://github.com/inkappdev/ink-music/releases/latest"><img src="https://img.shields.io/badge/Download-APK-f2f0eb?style=for-the-badge&logo=android&logoColor=000000&labelColor=f2f0eb" alt="Download the APK" height="40" /></a>
  &nbsp;
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/inkappdev/ink-music"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="40" /></a>
</p>

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="18.5%" alt="A lossless song's moment: LOSSLESS, 16 bit, 44.1 kHz across the cover" />&nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="18.5%" alt="Welcome: INK MUSIC over records developing in dither" />&nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/9.png" width="18.5%" alt="The player in the dither look: Madvillainy" />&nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="18.5%" alt="Listen: jump back in, your YouTube Music mixes and mixes for you, in dither" />&nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width="18.5%" alt="Synced lyrics over the cover in dither" />
</p>
<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/8.png" width="18.5%" alt="The player in the blur look" />&nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="18.5%" alt="An artist: their photo in dither, top songs as a chart" />&nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/7.png" width="18.5%" alt="ink radio: WFMU on its moving sleeve, saying what it plays" />&nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6.png" width="18.5%" alt="A record" />&nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/10.png" width="18.5%" alt="The player, plain" />
</p>

---

## Your music, from wherever it lives

| | |
|---|---|
| **The phone's files** | Everything in its music folder, filed by record and artist, with the covers your files carry. |
| **Your own server** | Navidrome or any Subsonic server: your whole library streamed from home, or **kept on the phone** to play with no signal. Loves are the server's stars and playlists are its playlists, so every app you use agrees. |
| **YouTube Music** | Search and play without an account, through [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor). Signed in: your playlists, likes, mixes and artists, saved and followed both ways, your uploads, and **Premium's 256 kbps** if you have it. `full` build only. |
| **Spotify playlists** | Brought in from a file ([Exportify](https://exportify.app) or Spotify's own data download), each song found in your music or on YouTube Music, then kept as a playlist. |
| **ink radio** | Independent, listener-powered stations chosen by hand, each saying what it's playing ([below](#ink-radio)). |

## ink radio

<img src=".github/radio.gif" width="240" align="right" alt="WFMU playing, its name sending out rings in 1-bit dither" />

Sixty-two independent, listener-powered and community stations, chosen by hand: KEXP in
Seattle, NTS in London, dublab in Los Angeles, WFMU in Jersey City, Radio alHara in Bethlehem,
and on round the world.

Each says what it's playing, from its stream or its own playlist, on the player, the lock screen,
the widget and the car, its name sending out rings in 1-bit dither all the while.

Thousands more by genre from [radio-browser.info](https://www.radio-browser.info).

<br clear="right" />

## Something to play the moment it opens

Pick a few artists you love and the **listen** page fills with their mixes and records, the songs
indie radio played most this week (KEXP, KCRW and WXPN, counted), ink radio, and what you played
lately. Even with nothing on the phone. And every Friday night, **ink weekly**: what we're
listening to at ink, picked by real people.

## Features

**The player**
- Whole-screen over the cover, in five looks: blur, fluted glass, pixels, dither, plain.
- Circle a thumb round the cover to scrub, as on an iPod; flick it sideways for the next song.
- Synced lyrics from [lrclib](https://lrclib.net), or YouTube Music's where lrclib has none.
- **An equalizer** of its own, ten bands, with corrections for thousands of **headphones** from [AutoEq](https://github.com/jaakkopasanen/AutoEq).
- **Even volume** (ReplayGain) from the files' own tags or the server's. A record played through keeps its own quiet and loud.
- **Soft pause**: the music eases out and back in. And it says exactly what reaches your ears: codec, bit depth, rate.
- Small words step up a brightness in **sunlight**, read by the light sensor, so it reads outdoors.
- **Motion** to suit the phone: full, or less on a slower one (no cascades or softening), as Android says by default.

**Listening**
- **Radio**: when the queue runs out, more like it. Your server's similar songs first, then YouTube Music's mix.
- **A path** from one song to another, a step at a time by how they sound, on Navidrome with [AudioMuse-AI](https://github.com/NeptuneHub/AudioMuse-AI). Keep it as a playlist.
- **Playlists** made and changed here, kept on your server.
- A sleep timer that eases the music out, shuffle and repeat, and an up next you can reorder.
- The library shuffled anew each time you open it, if you like.
- **Artist pages**: their photo in dither, top songs, records, the story, who fans also like.
- **Scrobbling** to [ListenBrainz](https://listenbrainz.org) and Last.fm.

**Everywhere else**
- **Play on** Chromecast and Google Nest speakers, TVs, speaker groups and DLNA receivers (Denon HEOS and others), sending the original hi-res file where the speaker can take it. No Google Play services needed.
- **Android Auto**: records, artists, lists, search, and "play … on ink music".
- A **home-screen widget**, the mini player in ink's look.

**Yours**
- **Your year**, any day of it: the minutes, and the songs, artists and records you played most.
- **Share** a song or your year as a poster sized for a story.

## Privacy

No ads, no accounts, no analytics, no tracking. Nothing of yours leaves the phone except to your
own server, and to the services that answer what you ask for:

| Service | What it receives | Why |
|---|---|---|
| Your Navidrome / Subsonic server | Sign-in (a salted token, never the password), plays, stars | Your library |
| [lrclib.net](https://lrclib.net) | A song's artist and title | Lyrics |
| YouTube Music (`full` build) | Your searches, the artists you pick; signed in, your sign-in and the songs you play (your history there, off in settings) | Search, mixes, radio, your library |
| [ListenBrainz](https://listenbrainz.org) / Last.fm, if you connect them | What you play | Scrobbling |
| [AutoEq](https://github.com/jaakkopasanen/AutoEq) on GitHub | Nothing personal, when you look up your headphones | Their tuning |
| [radio-browser.info](https://www.radio-browser.info) | Nothing personal | The station list |
| The stations' own playlists | Nothing personal | The indie chart, what's on air |

What you've played is kept on the phone only.

## Download

Signed APKs are attached to every [GitHub release](https://github.com/inkappdev/ink-music/releases),
with their SHA-256 sums. [Obtainium](https://github.com/ImranR98/Obtainium) keeps it up to date.
F-Droid is next. The `full` apks from releases have Last.fm; a build from source needs its own key.

| Build | Sources | Where |
|---|---|---|
| `ink-music-<version>.apk` (`full`) | Files, your server, YouTube Music, radio | GitHub releases, Obtainium |
| `ink-music-<version>-play.apk` (`play`) | Files, your server, radio. No NewPipe code at all | The only build that may go to Google Play |

The release certificate's SHA-256:
`92eb2c4ea8d70b897db7cf000356f72cc2f3e698c39bb31f4b62ba717747e663`
(check with `apksigner verify --print-certs`).

Android 12 or later.

## From the makers of ink

<p align="center">
  <img src=".github/ink/endless.png" width="24%" alt="ink's home screen: the time, your apps as words, and Deep Cuts playing" />
  <img src=".github/ink/house-on.png" width="24%" alt="ink's house: every light a little pixel lamp, glowing when it's on" />
  <img src=".github/ink/games-chrono.png" width="24%" alt="ink's games: Chrono Trigger on the SNES shelf, its cartridge sliding out" />
  <img src=".github/ink/given-12m.png" width="24%" alt="+12m: the time ink gave back from instagram" />
</p>
<p align="center">
  <img src=".github/ink/tv-twin-peaks.png" width="97%" alt="ink tv: your apps as big words, and Twin Peaks waiting where you left it" />
</p>

ink music is the music player from **[ink](https://inkhome.app)**, a typographic home screen for
Android phones and TVs, in the same look. Your apps as big words, the song playing along the
bottom from any player, and your time back from the feed:

- **Five seconds first.** Open instagram and ink waits: its name, how many times today, a line
  filling slowly. Then open it, or not now. Daily limits and focus when you want them.
- **Your phone as a console.** Point it at your games folder: box art, shelves, time played, each
  system opened in its emulator.
- **Your house as words.** With Home Assistant, every light a little pixel lamp; your scenes,
  cameras and doorbell.
- **ink tv.** The same ink on your TV, and from your phone it turns off the TV's ads and tracking
  over your wifi. "Put it back" undoes exactly what changed.

No accounts, no ads, no analytics. ink is in a free test on Google Play now, everything open:
**[join the test](https://inkhome.app/test/)** · [inkhome.app](https://inkhome.app)

## Building

JDK 21 and the Android SDK (API 37).

```sh
./gradlew :app:assembleFullDebug      # a debug build
./gradlew :app:assembleFullRelease    # a release build (R8)
tools/release.sh                      # both builds, signed, with SHA256SUMS, in build/release/
```

Release builds are signed with the key named in `keystore.properties` beside `settings.gradle.kts`
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; never committed). Without it they're
signed with the debug key. Each release raises `versionCode` and `versionName` in
`app/build.gradle.kts` and adds `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

The icon and store graphics are drawn from the bundled fonts: `python3 tools/make-icon.py`
(fontTools, Pillow).

Built with Kotlin and Jetpack Compose, [Media3](https://github.com/androidx/media) (ExoPlayer and a
MediaLibrarySession in its own process) and [Haze](https://github.com/chrisbanes/haze).

## How it's made

Built by Bronson with Claude (Anthropic's AI) as a coding partner: the design, the decisions and the
testing are mine. Every feature is tried on a real phone before it ships, testers' notes come back as
fixes within days, and ink weekly is picked by hand. It's all here to read, under the GPL, and every
piece of someone else's work it stands on is named below, with its license.

## Contributing

Issues and pull requests are welcome. ink music has a strong look (black, two typefaces, no icons
where a word works, no rounded corners), so for anything visible, open an issue first and we'll
talk it through.

## Thanks

- [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0), and the poToken
  provider from [NewPipe](https://github.com/TeamNewPipe/NewPipe) (GPL-3.0), ported.
- [Metrolist](https://github.com/mostafaalagamy/Metrolist) (GPL-3.0), for showing how YouTube Music
  can be done well on Android.
- [yt-dlp's challenge solver](https://github.com/yt-dlp/ejs) (Unlicense, with meriyah and astring), which
  works out a signed-in stream's link from YouTube's own player.
- [AutoEq](https://github.com/jaakkopasanen/AutoEq) (MIT), for the headphone tunings, from measurements by
  oratory1990, crinacle, Rtings and others.
- [Media3](https://github.com/androidx/media) and [Haze](https://github.com/chrisbanes/haze) (Apache-2.0).
- Archivo Black and Silkscreen, both under the SIL Open Font License.
- Lyrics from [lrclib.net](https://lrclib.net) and YouTube Music; stations from
  [radio-browser.info](https://www.radio-browser.info) and the stations themselves; scrobbling to
  [ListenBrainz](https://listenbrainz.org) and [Last.fm](https://www.last.fm).

## Not affiliated

ink music is not affiliated with, funded, authorized or endorsed by YouTube, Google LLC, Spotify,
Navidrome or any radio station. Trademarks belong to their owners.

## License

[GPL-3.0](LICENSE). Free, always: no paywall, ever.

<p align="center"><sub>From the makers of <a href="https://inkhome.app"><b>ink</b></a>, a home screen for Android phones and TVs.</sub></p>
