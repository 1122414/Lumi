<div align="center">

# Lumi

**Real-Time AI Photography Director for Android**  
*See the scene, frame the shot, and nail the composition — before you press the shutter.*

<p>
  <a href="./README.md"><strong>English</strong></a> ·
  <a href="./README.zh-CN.md">简体中文</a>
</p>

<p>
  <img src="https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-3DDC84?logo=android&logoColor=white" alt="Android 8.0+" />
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/Camera-CameraX%201.5-0F9D58" alt="CameraX" />
  <img src="https://img.shields.io/badge/On--Device%20Vision-ML%20Kit%20%2B%20MediaPipe-FF6F00" alt="ML Kit + MediaPipe" />
</p>

</div>

---

## Why Lumi?

Most camera apps focus on **post-processing** — endless filter packs, heavy beauty smoothing, or cloud retouching *after* a mediocre photo has already been taken.

**Lumi focuses on the moment before the shutter.**  
Using a **Hybrid Cloud + 60fps On-Device Architecture**, Lumi pairs a cloud Vision LLM as your **photography brain** with low-latency on-device sensors, segmentation, and tracking as your **framing stabilizer**. Instead of overwhelming you with jargon or grids, Lumi gives **one clear, actionable cue at a time** — guiding your phone smoothly into a better composition.

---

## Key Features

| Capability | What It Does |
| :--- | :--- |
| **Hybrid Cloud + 60fps Guidance** | On-device ML Kit detects faces, group portraits, and foreground candidates first; the cloud vision model picks the best subject, composition intent, and focal length via ultra-compact JSON (~1s response), while quaternion pose projection drives a silky 60fps green guidance ring locally. |
| **Tap-to-Lock Subject & AF/AE** | Tap any person or object in the viewfinder to lock your intent immediately. Lumi simultaneously triggers CameraX `FocusMeteringAction` on the selected subject and passes your tap marker to the model. |
| **Multi-Person & Object Support** | Automatically recognizes solo portraits, multi-face group shots, and everyday objects. Uses MediaPipe Magic Touch on-device segmentation with candidate-box fallback for slender or low-contrast subjects. |
| **3D Anti-Drift & Parallax Tracking** | A ~1 ms/frame local color tracker compensates for slight subject motion and close-range parallax, while elevation anchoring (`12°` guard) prevents false ring lock if you pitch the phone sharply away. |
| **Optical Zoom Wheel & Focal Hints** | Reads real Camera2 focal lengths and sensor dimensions to build hardware-accurate zoom stops (`0.6×`, `1×`, `2×`, `3×`, etc.). When the model recommends switching focal length, a one-tap pill jumps to that stop **without losing active composition guidance**. |
| **Instant Local Composition Presets** | Switch between `Auto`, `Rule of Thirds (L/R)`, `Symmetry`, `Tight`, and `Headroom` right in the viewfinder with zero network latency. |
| **Horizon Level, Haptics & Auto-Shoot** | A minimalist horizon bar flanks the center reticle and turns green within `±2.5°` (WYSIWYG with the ready gate). Enjoy subtle haptic feedback on alignment and an optional hands-free **Auto-Shoot** timer (`450 ms` steady hold). |
| **Privacy & Credential Security** | Frames sent for recommendation are processed in memory only. API keys are encrypted with Android Keystore AES-GCM, hidden by default, and protected with screenshot blocking on the settings dialog. |

---

## How It Works

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                         VIEWFINDER (60 fps)                             │
│  CameraX Preview · Precision Zoom Wheel · Horizon Level · Tap Subject   │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │
        ┌───────────────────────┴───────────────────────┐
        ▼                                               ▼
┌──────────────────────────────┐        ┌──────────────────────────────┐
│   On-Device Vision & Sensors │        │   Cloud Vision Brain (VLM)   │
│  • ML Kit Face & Object Cand.│ 512px  │  • Picks best candidate ID   │
│  • Tap Marker + Zoom Stops   ├───────►│  • Chooses framing intent    │
│  • MediaPipe Magic Touch     │ <───┐  │  • Suggests optical zoom     │
│  • 1ms/frame SubjectTracker  │     │  │  • Returns ≤10-char reason   │
│  • Quaternion PoseGuidance   │ JSON│  └──────────────────────────────┘
└──────────────┬───────────────┘     │
               └─────────────────────┘
                                │
                                ▼
    Smooth Green Ring + Subject Outline + Haptic Cue + Optional Auto-Shoot
```

1. **Aim or Tap**: Point at your scene (or tap a specific subject) and press **Recommend**.
2. **Instant Local Feedback**: Lumi highlights detected candidates / focus reticle within milliseconds while querying your configured vision endpoint.
3. **Follow the Green Ring**: Rotate or tilt the phone to align the green ring with the center dot and keep the horizon bar green.
4. **Capture**: Tap the shutter on the haptic **"Ready"** cue — or toggle **Auto-Shoot** for automatic capture once steady.

---

## Quick Start

### Prerequisites

- **JDK**: 17+ (JDK 21 bundled with Android Studio JBR works out of the box)
- **Android SDK**: Platform `36`, Build Tools `36.0.0`
- **Minimum Device**: Android 8.0 (`minSdk 26`), physical rear camera recommended

### Build, Test & Install

```bash
# Optional on macOS when using Android Studio's bundled JDK
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# Run unit tests, assemble debug APK, and run lint
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug

# Install directly to a connected Android device via ADB
./gradlew :app:installDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Model Configuration

Lumi works with any **OpenAI-compatible Vision Chat Completions API** supporting inline Base64 JPEG `image_url` inputs.

### Option A: Configure In-App (Recommended)

1. Launch Lumi and tap **Model** (`模型`) in the top bar.
2. Enter your **Base URL** (e.g. `https://your-service.example/v1` — `/chat/completions` is appended automatically), **Model Name**, and **API Key**.
3. Tap **Test Connection** to verify latency with a built-in `96 × 96` probe image, then tap **Save**.

> **Transport & Local Testing:**  
> Release builds enforce `HTTPS`. Debug builds also allow `HTTP` over trusted LAN (e.g. `http://192.168.1.20:3425/v1`) or USB via `adb reverse tcp:3425 tcp:3425` (`http://127.0.0.1:3425/v1`).

### Option B: Debug Build Defaults (`local.properties`)

For personal development, you can pre-populate debug defaults in your local (git-ignored) `local.properties`:

```properties
LUMI_LLM_BASE_URL=https://your-service.example/v1
LUMI_LLM_MODEL=your-vision-model
LUMI_LLM_API_KEY=your-local-test-key
```

> **Security Note:** Never commit `local.properties` or distribute debug APKs containing personal API keys. In-app saved settings always take precedence; release builds start with an empty configuration.

---

## Roadmap

- [x] **Stage S1 — Core Hybrid Experience**
  - On-device face/object candidate numbering, multi-person group framing & tap-to-select subject
  - Optical zoom recommendations with seamless guidance carry-over
  - Horizon level bar, haptic ready cue, auto-shoot toggle & anti-drift elevation anchoring
  - 512 px / compact JSON cloud pipeline + subject-linked AF/AE focus & metering
- [x] **Stage S2 — Real-Time Photography Director**
  - Single highest-priority director cue per scene (composition, background clearance, lighting, or 1-line portrait pose tip)
  - Adjustable AI proactivity modes (`Quiet` / `Proactive` / `Manual`)
  - 3-second post-shot mini review + zero-cost on-device aesthetic recrop suggestions (preserves original negative)
- [x] **Stage S3 — Spatial Moat & Aesthetic Memory**
  - Cross-parallax **"step sideways / step closer"** guidance with live background-anchor progress bar
  - 4-dimension post-shot **"Good Photo" evaluation** (`Subject`, `Balance`, `Background`, `Naturalness`) + on-device personal aesthetic preference memory feeding the VLM prior
- [x] **Stage S4 — Pro Exposure & Light Telemetry Layer**
  - Real-time physical light telemetry (`ISO`, shutter speed, aperture, `EV`, ambient `Lux`, highlight/shadow clipping, and subject-vs-background backlit ratio) fed to the VLM director
  - Three exposure control modes (`Auto` / `AI Auto` / `M Manual`) integrated with proactivity levels (confirmable `[Apply]` pill in `Manual` mode; smooth auto-apply + `[Revert]` in `Proactive` mode)
  - Per-item `AUTO` semi-automatic priority in `M Manual` (`EV`, `ISO`, `S`, `WB`, plus hardware-gated aperture `F`), one-tap `Copy AI` and `All AUTO`
- [x] **Stage S5 — Reference Shot Coach (`Copy + Beyond-Copy`)**
  - Optional reference photo upload with thumbnail + toggleable 25% translucent ghost overlay (adjustable opacity) & cached Shot Recipe card
  - Direct speakable portrait pose-correction cues + **beyond-copy upgrade suggestions** (same spot, better photo)

For detailed strategy and architecture decisions, see [`plan_/11.26/2026-11-26_Lumi下一步产品与技术战略.md`](plan_/11.26/2026-11-26_Lumi下一步产品与技术战略.md).

---

## Project Structure

```text
Lumi/
├── app/src/main/java/com/lumi/camera/
│   ├── camera/        # CameraX session, optical zoom stops, sensor pose & geometry
│   ├── guidance/      # Quaternion rotation guidance, horizon roll & steady-hold logic
│   ├── shotplan/      # Candidate protocol, composition choices, VLM client & target anchoring
│   ├── vision/        # ML Kit analyzer, MediaPipe segmentation, 2D SubjectTracker
│   └── ui/            # Jetpack Compose viewfinder, zoom wheel, model settings & diagnostics
├── app/src/test/      # Pure Kotlin unit tests (optics, guidance, protocol, tracker, fallback)
├── tools/m1_framing/  # Standalone offline framing comparison utility
├── plan_/             # Product strategy & milestone architecture docs
└── AGENTS.md          # Engineering & Conventional Commit conventions
```

---

## On-Device Models & Attribution

Foreground subject segmentation runs fully on-device using **MediaPipe Tasks Vision 0.10.35** with the bundled **Google Magic Touch v1** model:

- **Model**: `magic_touch.tflite` (`6,227,884` bytes)
- **SHA-256**: `e24338a717c1b7ad8d159666677ef400babb7f33b8ad60c4d96db4ecf694cd25`
- **Resources**: [Model Source](https://storage.googleapis.com/mediapipe-models/interactive_segmenter/magic_touch/float32/1/magic_touch.tflite) · [Google Model Card](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MagicTouch.pdf) · [Apache 2.0 License](app/src/main/assets/magic_touch.LICENSE.txt)

Also includes [`tools/m1_framing`](tools/m1_framing/README.md) for offline framing evaluation.
