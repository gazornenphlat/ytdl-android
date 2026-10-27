# ytdl for Android

Automatically downloads new videos from the YouTube channels you follow, entirely on your phone.
It's the Android version of the ytdl PC tray app, with no PC needed and no connection to it.

## Features
- **Watches your channels.** It reads each channel's YouTube feed and queues new uploads as soon as they appear.
- **Smart schedule.** It learns when each channel usually uploads and checks around those times. Every channel is still checked at least once a day.
- **Video or WAV per channel**, plus a choice of video quality (480p / 720p / 1080p / best).
- **Download over mobile data: on or off.** When it's off, videos wait in the queue until you're on Wi-Fi.
- **Optional "only while charging"** setting, and 1–4 downloads at once.
- **Retries failed videos,** then sets aside ones that keep failing. You can retry or remove them from the Queue tab.
- **Skips live streams** until they've ended.
- **Imports your PC channels** from FlexGet's `config.yml`, including names, lists and WAV settings. It can also back up and restore its own channel list.
- **Share a YouTube link to ytdl** from the YouTube app to add that channel.
- **YouTube sign-in** for when YouTube asks to "confirm you're not a bot".
- **yt-dlp updates itself daily.** yt-dlp, Python and ffmpeg are all bundled in the app.

## Install
1. Open the **Releases** page of this repository on your phone and download the newest APK.
   Pick `app-arm64-v8a-release.apk`, which suits almost all phones from the last 7+ years, or `app-universal-release.apk` if you're unsure.
2. Open the file. Android will ask you to allow installs from your browser or file manager the first time; allow it.
3. Open **ytdl** and follow the four setup steps:
   1. Choose where videos are saved.
   2. Decide whether to allow mobile data.
   3. Optionally import your channels.
   4. Allow it to run in the background.

New builds install over the old one and keep your channels and settings.

> This app isn't on the Play Store, because Google doesn't allow YouTube downloaders there. Installing the APK yourself is normal for apps like this.

## Tips
- **To bring channels over from the PC:** copy `T:\sync\MOVIES\yt-dlp\config.yml` to your phone (by USB, email or Drive), then go to **Settings → Import from the PC**.
- **If checks seem to be skipped:** go to **Settings → Battery optimisation → Allow**. Samsung, Xiaomi and OnePlus phones may also need the app's battery setting changed to **Unrestricted**.
- **Files are saved** to `<your chosen folder>/<list name>/Channel - Title.mp4` (or `.wav`).
- **Data and storage:** 1080p video uses about 0.5–1.5 GB per hour of video. WAV uses about 600 MB per hour of audio.

## Building
GitHub Actions builds the APK on every push to `main` (`.github/workflows/build.yml`).
The APKs are attached to a new release called `ytdl <build number>`.
To build locally, use Android Studio, or run `gradle :app:assembleRelease` with JDK 17 and the Android SDK.

The release signing key is **not** in the repository. It's stored as the `YTDL_KEYSTORE` Actions secret
(the base64 of `ytdl-release.jks`) and restored during the build. Every build is signed with the same key,
so each new APK installs as an update. Keep a private copy of the `.jks` file; losing it means
reinstalling from scratch.

## Tech
Kotlin, Jetpack Compose (with an AER neumorphic design that matches the PC version),
WorkManager (15-minute background checks), and [youtubedl-android](https://github.com/JunkFood02/youtubedl-android),
which bundles yt-dlp, Python and ffmpeg. Files are saved through Android's Storage Access Framework, so SD cards work.
