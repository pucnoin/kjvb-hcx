# Thermal Stress Test (CPU / GPU)

Android app. Works on Android 5.1.1 (API 22) up to Android 13 (API 33) and newer.
No extra libraries.

## How to get the APK

### Option A - Android Studio (on a PC/Mac)
1. Install Android Studio (Electric Eel or newer) and open this folder.
2. Wait for "Gradle sync" to finish.
3. Menu: Build > Build Bundle(s) / APK(s) > Build APK(s).
4. The file is at `app/build/outputs/apk/debug/app-debug.apk`.

### Option B - No install, build online with GitHub
1. Make a free GitHub account, create a new repository, upload everything in this folder
   (keep the `.github` folder).
2. Open the "Actions" tab > "Build APK" > "Run workflow".
3. When it is green, open the run and download the artifact `ThermalStress-apk`
   (it contains `app-debug.apk`).

### Install on the phone
Copy `app-debug.apk` to the phone, open it, and allow "Install unknown apps" for your
file manager/browser when Android asks. (Android 5.1.1: Settings > Security > Unknown sources.)

## Notes
- Battery temperature is always shown with 0.1 degree accuracy.
- CPU/GPU temperatures come from /sys/class/thermal. Many phones, especially Android 10+,
  block apps from reading them - then the app shows N/A (and says which sensors it found).
- Throttling is detected three ways: measured speed dropping below 85% of its peak,
  the Android thermal status (Android 10+ only), and the CPU clock line.
- Keep the screen on and the app in front while testing. The test stops if the app goes
  to the background.
- Extreme mode is heavy. The safety stop (battery 45.0 C / CPU or GPU 95.0 C) is on by default.
