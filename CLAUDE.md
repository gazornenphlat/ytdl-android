# CLAUDE.md

Guidance for Claude working on **ytdl for Android**. This is a standalone app with no link to the
PC version, though the logic was ported from it. If `memory.md` exists, read it too (private, git-ignored).

## What it does
Watches YouTube channels through their RSS feeds (`https://www.youtube.com/feeds/videos.xml?channel_id=UC…`),
queues new uploads, and downloads them on the phone with yt-dlp (bundled via youtubedl-android) into
a user-picked folder: `<tree>/<list>/<Channel - Title>.<ext>`.

## Layout (`app/src/main/java/app/ytdl/`)
| File | Role |
|---|---|
| `YtdlApp.kt` | Application: init Store, notification channels, schedule the periodic worker, unpack yt-dlp |
| `core/Store.kt` | All state as JSON files in `filesDir` plus `SharedPreferences` settings (`Store.S`). `Store.changes` flow drives UI refresh |
| `core/Feeds.kt` | RSS fetch/parse (XmlPullParser) |
| `core/Scheduler.kt` | Smart schedule, ported from the PC `ytdl_schedule.py` (window, busy and unknown rules, 24 h safety net) |
| `core/Lookup.kt` | Link/@handle/video/ID → channel info, plus `validateChannel` |
| `core/Importer.kt` | FlexGet `config.yml` import (regex parse, same rules as the PC) and the app's own JSON backup |
| `core/Downloader.kt` | yt-dlp request building, outcome classification (OK/FAILED/LIVE/COOKIES), copy into the SAF tree |
| `work/CheckWorker.kt` | WorkManager job: feeds → queue → downloads (foreground, dataSync). Network and charging rules live in `whyNotDownload()` |
| `ui/Neu.kt` | AER design system: palette, `neuRaised`/`neuInset` shadows, NeuButton/Toggle/Field/Pill/Check, Dial |
| `ui/*Screen.kt` | Home (dial), Channels (+Add/Edit dialogs), Schedule, Queue (waiting + failed), Log, Settings, Setup |
| `ui/LoginActivity.kt` | WebView sign-in that writes `cookies.txt` (Netscape) for `--cookies` |

## Building
There's no local SDK in Claude's sandbox (Google/Maven hosts are blocked), so **GitHub Actions is
the build**. Push to `main`, then check the workflow run. Fix compile errors from the log.
Versions: AGP 8.5.2, Kotlin 2.0.20 (compose plugin), Gradle 8.9 via `gradle/actions/setup-gradle`,
JDK 17, compileSdk/targetSdk 34, minSdk 26, youtubedl-android 0.17.2.

## Rules
- **Mobile data is opt-in.** `Store.S.allowMobile` defaults to false. Never download on a metered or
  cellular network unless it's on. Every download path must go through `CheckWorker.whyNotDownload()`.
- **Adding or importing a channel must not download its backlog** unless `backlogOnAdd` is on. The first
  check marks the existing feed entries as seen (`ChannelState.primed`).
- **No connection to the PC app.** Don't add sync features unless the owner asks.
- **Keep the signing key and config stable** (`YTDL_KEYSTORE` secret, `applicationId app.ytdl`). Never commit the `.jks`; the repo is public. Changing
  either breaks updating over the installed app and would lose the user's data.
- **UI must use the AER components in `ui/Neu.kt`** (palette `#E8EEF2` / `#c3ccd4` / `#fff` /
  `#4db8e8` / `#4a5a66`). Don't use stock Material components with their own look.
- **Plain-language user-facing text.** The owner isn't a developer.
- **Long work stays in `CheckWorker`** (foreground with a notification). Never run downloads from the UI thread.
