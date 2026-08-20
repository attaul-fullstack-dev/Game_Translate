# Game Translator

Android app for capturing a selected game-screen area, recognizing English text
with ML Kit OCR, translating it to Indonesian on-device, and showing the result
as a system overlay.

## Requirements

- Android Studio with JDK 21
- Android SDK 36
- Android device or emulator running API 26+
- Internet access for the first ML Kit translation-model download

No Gemini API key is required. OCR and translation use Google ML Kit.

## Run locally

1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Run the `app` configuration on an emulator or physical device.
5. Grant overlay, notification, and screen-capture permissions when prompted.

The first launch downloads the English and Indonesian translation models.

## Overlay controls

- Tap the bubble to select an area, pause, or resume translation.
- Long-press the bubble to select a different OCR area.
- Stop the translator from the notification or the app's Settings tab.

## Continuous integration

GitHub Actions provisions Gradle 9.5.1 with JDK 21 to run unit tests, Android
lint, and the debug APK build. The repository intentionally relies on
`gradle/actions/setup-gradle`, so CI does not require a checked-in Gradle
wrapper. Successful runs upload the APK as `game-translator-debug`.
