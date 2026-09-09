# Voice Text - Android Voice Recording App

A beautiful, user-friendly Android voice recording app with a warm Ghibli-inspired design. Record audio, transcribe to text, and translate to multiple languages.

## Features

- **Project-Based Recording** — Organize recordings into named projects
- **Audio Recording** — Record high-quality M4A (AAC) files with sound wave visualization
- **Sound Wave Animation** — Real-time audio amplitude visualization while recording
- **Voice-to-Text** — Transcribe recorded audio to text using system speech recognition
- **Translation** — Translate transcriptions to 15+ languages using Google Translate API
- **Audio Export** — Export recordings as MP3 or M4A to any folder via Storage Access Framework
- **SQLite Storage** — Projects, recordings, transcriptions, and translations stored locally via Room
- **Import Audio** — Import existing audio files from device storage
- **Ghibli-Inspired UI** — Warm nature tones, rounded corners, soft shadows, friendly design

## Screens

### Main Page (Project List)
- View all projects with recording counts
- Create new projects
- Access settings

### Recording Screen
- Large microphone button to start/stop recording
- Sound wave animation showing audio amplitude
- Chronometer showing recording duration
- Import audio button

### Recordings Page (Project Detail)
- List all recordings in a project
- Play audio files
- Voice-to-Text button — transcribes audio and saves to database
- Translate button — translates text to target language
- Export Audio button — export as MP3 or M4A
- Delete recordings

### Voice-to-Text Flow
1. Click "Voice to Text" on a recording
2. Audio plays through speaker
3. System speech dialog captures the speech
4. Transcription saved to database and displayed

### Translation Flow
1. Click "Translate" on a recording with transcription
2. Text translates to target language set in settings
3. Translated text displayed below original

### Settings Page
- Speech Language — language for voice recognition
- Translation Language — target language for translation
- Export Format — Auto (MP3/AAC), MP3 only, or AAC only

## Supported Languages

| Language | Code |
|----------|------|
| English | EN |
| 简体中文 | ZH-CN |
| 繁體中文 | ZH-TW |
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

## Architecture

```
com.voicetext/
├── data/
│   ├── Database.kt          - Room database, entities, DAOs
│   ├── DatabaseProvider.kt  - Database singleton extension
│   ├── SettingsManager.kt   - SharedPreferences for settings
│   ├── TranslationHelper.kt - Google Translate free API
│   └── Mp3Exporter.kt       - Audio export (M4A → MP3/AAC)
├── ui/
│   ├── MainActivity.kt          - Project list screen
│   ├── ProjectDetailActivity.kt - Recordings with voice-to-text & translate
│   ├── RecordActivity.kt        - Audio recording with sound wave
│   ├── SoundWaveView.kt         - Custom sound wave animation view
│   └── SettingsActivity.kt      - App settings
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
| Android SpeechRecognizer | built-in | System speech dialog |
| Google Translate API | free | Translation without API key |

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

### Set `JAVA_HOME` to your JDK 17 install:
   - **Windows (PowerShell):**
     ```powershell
     $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
     ```
   - **Windows (cmd):**
     ```bat
     set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
     ```
   - **macOS / Linux:**
     ```bash
     export JAVA_HOME=$(/usr/libexec/java_home -v 17)
     ```

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
- `INTERNET` — Required for translation API
- `READ_EXTERNAL_STORAGE` — For importing audio files (API < 33)
- `READ_MEDIA_AUDIO` — For importing audio files (API 33+)

## How It Works

### Audio Recording
Uses `MediaRecorder` with AAC encoding at 44.1kHz / 128kbps, saved as M4A files in app external storage. Sound wave animation reads `maxAmplitude` from the recorder for real-time visualization.

### Voice-to-Text
Uses Android's system speech recognition dialog (`RecognizerIntent`). The recorded audio plays through the speaker, and the dialog captures the speech for transcription. Results are saved to the database.

### Translation
Uses Google Translate's free API endpoint (`translate.googleapis.com`). No API key or model downloads required. Supports 15+ languages.

### Audio Export
Uses `MediaCodec` to transcode M4A to MP3 or AAC. The user picks the export location via Android's Storage Access Framework (SAF).

### Database
Room database with two tables:
- **projects** — Project metadata (name, creation date, recording count)
- **recordings** — Recording data (audio path, duration, transcribed text, translated text, translation language)

### Settings
Uses `SharedPreferences` for:
- Speech language (for recognition)
- Translation language (target language)
- Export format (Auto/MP3/AAC)

## License

MIT License
