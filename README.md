# Voice Text - Android Voice-to-Text App

A beautiful, user-friendly Android voice-to-text app with a warm Ghibli-inspired design. Record speech, get live captions, and translate to multiple languages in real-time.

## Features

- **Project-Based Recording** — Organize recordings into named projects
- **Live Speech-to-Text** — Real-time captions while recording using Android SpeechRecognizer
- **Real-Time Translation** — Translate speech to 15+ languages on the fly using Google ML Kit
- **Audio Recording** — Save recordings as M4A (AAC) files
- **SQLite Storage** — Projects, recordings, transcriptions, and translations stored locally via Room
- **Import Audio** — Import existing audio files from device storage
- **Playback** — Play back recordings with transcribed text
- **Ghibli-Inspired UI** — Warm nature tones, rounded corners, soft shadows, friendly design

## Screens

### Main Page (Project List)
- View all projects with recording counts
- Create new projects
- Quick access to real-time OCR/translation
- Settings

### Recording Screen
- Large microphone button to start/stop recording
- Live caption panel showing original text (white)
- Live translation panel showing translated text (orange)
- Chronometer showing recording duration
- Import audio button
- Language selection button

### Real-Time OCR Screen
- Live camera preview with text detection overlay
- Detected text highlighted with green boxes
- Real-time translation display
- Copy text to clipboard
- 12 translation directions supported

### Project Detail Screen
- List all recordings in a project
- Play audio files
- View transcribed and translated text
- Delete recordings

## Supported Translation Languages

| Language | Code |
|----------|------|
| English | EN |
| 简体中文 | ZH |
| 繁體中文 | ZH |
| 日本語 | JA |
| 한국어 | KO |
| Français | FR |
| Deutsch | DE |
| Español | ES |
| Русский | RU |
| العربية | AR |
| हिन्दी | HI |
| Tiếng Việt | VI |
| ไทย | TH |
| Italiano | IT |
| Português | PT |

## Translation Directions
- English → 简体中文 / 繁體中文 / 日本語 / 한국어 / Français / Deutsch / Español
- 简体中文 → English
- 繁體中文 → English
- 日本語 → English
- 한국어 → English

## Architecture

```
com.voicetext/
├── data/
│   ├── Database.kt          - Room database, entities, DAOs
│   ├── DatabaseProvider.kt  - Database singleton
│   └── TranslationHelper.kt - ML Kit translation with model management
├── ui/
│   ├── MainActivity.kt          - Project list screen
│   ├── ProjectDetailActivity.kt - Recording list for a project
│   ├── RecordActivity.kt        - Recording with live captions
│   ├── RealTimeOcrActivity.kt   - Real-time OCR with translation
│   ├── SettingsActivity.kt      - App settings
│   └── OcrOverlayView.kt        - Custom view for text detection overlay
└── res/
    ├── layout/              - Activity and item layouts
    ├── drawable/            - Icons and shapes
    ├── values/              - Colors, strings, themes
    └── mipmap-anydpi-v26/   - Adaptive launcher icons
```

## Tech Stack

| Library | Version | Purpose |
|---------|---------|---------|
| AndroidX Core | 1.12.0 | Framework |
| Material Components | 1.11.0 | UI components |
| Room | 2.6.1 | SQLite database |
| KSP | 1.9.20-1.0.14 | Room annotation processing |
| Kotlin Coroutines | 1.7.3 | Async processing |
| ML Kit Translation | 17.0.2 | On-device translation |
| Android SpeechRecognizer | built-in | Speech-to-text |

## Build Instructions

### Requirements

- Android Studio Hedgehog (2023.1.1) or later
- **JDK 17** (required by AGP 8.2.0)
- Android SDK 34 (compileSdk/targetSdk)
- Min SDK 24 (Android 7.0)

### Build from Android Studio

1. Open the project in Android Studio
2. Let Gradle sync and download dependencies
3. Select **Build → Build Bundle(s) / APK(s) → Build APK(s)**

### Build from Command Line

```powershell
# Set JDK 17 (required)
$env:JAVA_HOME = "C:\path\to\jdk-17"

# Build debug
.\gradlew assembleDebug

# Build release
.\gradlew assembleRelease
```

### Signing Configuration

The release build is configured to sign with a keystore (`release.keystore`) in the project root:

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("../release.keystore")
        storePassword = "voicetext"
        keyAlias = "voicetext"
        keyPassword = "voicetext"
    }
}
```

> **Note:** The included `release.keystore` is for development/testing. For production distribution, generate your own keystore with a strong password and keep it secure.

To generate your own keystore:

```powershell
keytool -genkeypair -v -keystore release.keystore -alias voicetext `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass <your-password> -keypass <your-password> `
  -dname "CN=Voice Text, OU=Dev, O=VoiceText, L=City, ST=State, C=US"
```

## Install

```bash
adb install app/build/outputs/apk/release/app-release.apk
```

## Design

The app uses a Ghibli-inspired color palette:
- **Primary**: Soft forest green `#5B8C5A`
- **Accent**: Warm peach `#E8A87C`
- **Background**: Warm cream `#FFF9F0`
- **Cards**: Soft white with rounded corners and subtle shadows
- **Recording indicator**: Warm red `#D4685C`

## Permissions

- `RECORD_AUDIO` — Required for voice recording
- `READ_EXTERNAL_STORAGE` — For importing audio files (API < 33)
- `READ_MEDIA_AUDIO` — For importing audio files (API 33+)

## How It Works

### Speech Recognition
Uses Android's built-in `SpeechRecognizer` API with partial results for real-time captions. The recognizer restarts automatically after each utterance for continuous listening.

### Translation
Uses Google ML Kit's on-device translation. Language models are downloaded on first use and cached for offline use. The translator is invoked for each detected speech segment.

### Audio Recording
Uses `MediaRecorder` with AAC encoding at 44.1kHz / 128kbps, saved as M4A files in app external storage.

### Database
Room database with two tables:
- **projects** — Project metadata (name, creation date, recording count)
- **recordings** — Recording data (audio path, duration, transcribed text, translated text)

## License

MIT License
