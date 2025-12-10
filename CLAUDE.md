# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Context: S25-Lab-Listener
**Target Device:** Samsung Galaxy S25 Ultra (Android 15+, Snapdragon 8 Elite).
**Core Architecture:**
- **Headless Foreground Service:** Must run indefinitely (24/7) with a persistent notification.
- **AI Model:** OpenAI Whisper Small/Medium (Quantized .tflite) running on **TensorFlow Lite with QNN/GPU Delegate** (Qualcomm Neural Processing Unit).
- **Network:** WebSocket Client (OkHttp) streaming JSON packets to `ws://192.168.x.x`.
- **Bookmark Feature:** A "BOOKMARK" action button in the notification that sends a specific JSON flag to the server.

## Coding Standards
- **Language:** Kotlin (Android).
- **Build System:** Gradle Kotlin DSL (`build.gradle.kts`).
- **Permissions:** STRICT compliance with Android 15 `FOREGROUND_SERVICE_MICROPHONE` requirements.
- **Dependency Handling:** Do NOT use deprecated APIs. Use `libs.versions.toml` for version management.
- **Model Management:** Models are stored in `src/main/assets`.

## Project Configuration
- **Compile SDK:** 36
- **Min SDK:** 35 (Android 15)
- **Target SDK:** 36
- **JVM Target:** Java 17
- **Kotlin Version:** 2.0.21

## Build Commands
- Build project: `gradlew build`
- Clean build: `gradlew clean build`
- Build debug APK: `gradlew assembleDebug`
- Install on connected device: `gradlew installDebug`

## Testing Commands
- Run unit tests (JVM): `gradlew test`
- Run instrumented tests (on device): `gradlew connectedAndroidTest`

## Code Quality Commands
- Lint the code: `gradlew lint`
- Generate lint report: `gradlew lintDebug`

## Dependency Management
Dependencies are managed via version catalog in `gradle/libs.versions.toml`. When adding new dependencies, add the version to `[versions]`, the library to `[libraries]`, and reference it in `app/build.gradle.kts` using `libs.<dependency-name>`.

## Architecture Notes
This is **NOT** a minimal scaffold. It is a headless AI transcription agent.
- **Entry Point:** `MainActivity` (Configuration UI).
- **Core Service:** `TranscriptionService` (Foreground).
- **Logic:** `AudioEngine` (TFLite) + `NetworkClient` (WebSockets).