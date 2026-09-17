# Meeting Coach Android

A local-only Android meeting speaking coach. The app embeds the UI in a WebView and uses Android microphone permission. No server or network permission is required.

## Build on Bazzite

```bash
cd ~/containers
git clone https://github.com/cguhher/meeting-coach.git
cd meeting-coach
chmod +x build.sh
./build.sh
```

For updates:

```bash
git pull
./build.sh
```

The first build downloads the container base image, Android SDK components and Gradle dependencies.

APK output:

`app/build/outputs/apk/debug/app-debug.apk`

## Install

Copy the APK to the Android tablet and open it. Android may require permission to install apps from the app used to open the APK. The app itself then asks for microphone permission.

## Behaviour

- Grey: not currently classified as Chris
- Green: Chris speaking for <30 seconds
- Amber: 30-60 seconds
- Red: >60 seconds
- 1.1 second grace period joins short pauses/breaths
- Rolling speaking share uses the last 10 minutes, or meeting elapsed time for the first 10 minutes
- Threshold is saved locally
- Screen stays awake while app is open
- Calibration samples "others" and "me" separately and suggests a midpoint threshold

This is an amplitude classifier, not speaker recognition. Calibration quality depends on tablet position and desk-speaker volume.
