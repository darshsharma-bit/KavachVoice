# KavachVoice

Real-time acoustic AI voice cloning detection and contextual fraud interception defense system.

---

## Overview

KavachVoice is a multi-tier security framework designed to detect and mitigate synthetic voice impersonation attacks targeting financial transactions. Modern voice cloning fraud typically relies on social engineering and urgency cues (e.g., impersonating family members or law enforcement) to coerce victims into executing immediate UPI fund transfers while keeping them engaged on an active cellular or VoIP call.

Conventional defenses either inspect network metadata after packets leave the device or rely on post-incident reporting after funds have already cleared. KavachVoice operates proactively by correlating real-time mobile device context (active phone calls and foregrounded banking applications) with an on-device acoustic pipeline and an ensemble deep-learning anti-spoofing backend.

---

## Architecture

The system is organized into decoupled client-side and server-side components:

```
+-------------------------------------------------------------------------+
|                           KAVACHVOICE SYSTEM                            |
+------------------------------------+------------------------------------+
|        CLIENT-SIDE DEFENSE         |        SERVER-SIDE ENGINE          |
|    (Android Client · API 26+)      |  (FastAPI Backend · React Console) |
|                                    |                                    |
|  +------------------------------+  |  +------------------------------+  |
|  | CallGuard Engine             |  |  | VoiceID Detection Core       |  |
|  | * WindowStateChanged events  |  |  | * RawNet2 Sinc-convolutions  |  |
|  | * Cellular + VoIP call state |  |  | * ECAPA-TDNN embeddings      |  |
|  | * AudioRecord 16kHz capture  |  |  | * Multi-model score fusion   |  |
|  | * 2.0s rolling audio buffer  |  |  | * Asynchronous inference     |  |
|  | * Frame-based VAD gate       |  |  +--------------+---------------+  |
|  | * Deterministic risk engine  |  |                 |                  |
|  | * System overlay alerts      |  |  +--------------v---------------+  |
|  +--------------+---------------+  |  | Evidence & Incident Dossier  |  |
|                 |                  |  | * SHA-256 audio verification |  |
|                 | (HTTP Multipart) |  | * Structured forensic report |  |
|                 +-------------------->| * NCRP-compatible metadata   |  |
|                                    |  | * Real-time WebSocket feed   |  |
|  +------------------------------+  |  +------------------------------+  |
|  | VoiceArmor (OEM Blueprint)   |  |                                    |
|  | * UAP perturbation concept   |  |  +------------------------------+  |
|  | * Isolated in consumer build |  |  | Operator Dashboard           |  |
|  +------------------------------+  |  | * Live alert feed & triage   |  |
|                                    |  | * Manual audio analysis      |  |
|                                    |  | * Forensic report downloads  |  |
|                                    |  +------------------------------+  |
+------------------------------------+------------------------------------+
```

---

## System Components

### 1. Android Client (`android/`)
The client application runs as an Android user-space application (minSdk 26, targetSdk 35) combining two background services:
- **`KavachAccessibilityService`**: Monitors window transitions (`AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED`) to identify foregrounded financial applications and synchronizes telephony/audio state.
- **`KavachForegroundService`**: Maintains background execution priority with the `microphone` foreground service type and hosts the service restart broadcast receiver.

### 2. VoiceID Anti-Spoofing Backend (`backend/`)
A Python FastAPI microservice providing:
- Asynchronous multi-model inference combining raw time-domain anti-spoofing (`RawNet2`) with speaker embedding consistency (`ECAPA-TDNN`).
- Strict audio validation (finiteness, amplitude, RMS energy, and duration checks).
- Weighted score fusion with deterministic decision boundaries.
- PDF evidence generation via ReportLab with SHA-256 audio digests.
- WebSocket alert broadcasting (`/ws/alerts`) for real-time triage.

### 3. Contact Center Dashboard (`dashboard/`)
A React 18 single-page application built with Vite and Tailwind CSS:
- Connects to the backend WebSocket stream for real-time incident telemetry.
- Provides a drag-and-drop audio inspection console for ad-hoc WAV file analysis.
- Displays comprehensive verdict cards with individual model outputs, confidence metrics, and latency.
- Allows immediate download of generated PDF forensic evidence dossiers.

### 4. VoiceArmor (OEM / HAL Blueprint)
A proactive defense concept implemented as a prototype in `VoiceArmorEngine.kt`:
- Evaluates the injection of psychoacoustically bounded Universal Adversarial Perturbations (UAP) into microphone PCM buffers to disrupt neural vocoder synthesis (e.g., HiFi-GAN, MelGAN) if caller audio is recorded for unauthorized cloning.
- **Implementation Status**: In consumer Android user-space builds, `VoiceArmorEngine` is deactivated and isolated because standard Android audio HAL enforces single-client microphone acquisition. Dual `AudioRecord` instances produce HAL resource conflicts. VoiceArmor serves as the architectural reference for hardware-level integration directly inside the OEM audio DSP / HAL path.

---

## Android Platform Architecture & Ingestion Reality

Under standard Android security architecture (AOSP / Android 10–15), third-party unprivileged applications executing in user-space cannot arbitrarily capture or tap the private downlink PCM audio stream of another application's cellular or VoIP phone call.

To implement acoustic voice protection within standard Android platform boundaries:
- **Downlink Ingestion Method**: Remote caller speech must be played via speakerphone or loudspeaker, allowing caller speech to be acoustically captured by the physical device microphone (`AudioRecord.MIC`).
- **Zero Audio Persistence**: Audio buffers are processed purely in volatile memory. No microphone buffers or WAV files are ever written to persistent device storage or uploaded to third-party cloud providers.
- **Microphone Hardware Lifecycle**: The physical microphone is allocated strictly upon active call detection and is immediately stopped and released (`recorder.release()`) when the call terminates.
- **Non-Invasive Overlay Intervention**: Visual warnings are displayed using `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY`. The overlay provides high-friction advisory warnings, explainable technical metrics, and a direct dialer shortcut to the national cybercrime helpline (`1930`). It cannot and does not freeze the operating system or terminate other applications.

---

## Audio Processing Pipeline

The on-device audio pipeline is implemented in `KeywordScanner.kt` and operates as the single authoritative microphone consumer for the Android client:

```
[Physical Microphone]
        |
        v (16 kHz, Mono, 16-bit PCM)
[AudioRecord Buffer] (4096 bytes read chunks)
        |
        v
[Rolling Circular Buffer] (32,000 samples = 2.0 seconds)
        |
        +---> [Frame-Level VAD] (20ms frames / 320 samples, threshold: 0.012f)
        |
        +---> [Speech Activity Gate]
        |     * Window RMS >= 0.018f
        |     * Window Peak >= 0.040f
        |     * Active Speech Duration >= 500 ms
        |
        v (If gate passes, every 800ms hop)
[VoiceIdClient Dispatch] (Multipart WAV -> /api/v1/analyze)
        |
        v (In-Flight Protection: drops overlapping dispatch if busy)
[Async Backend Evaluation]
```

### Pipeline Specifications

| Parameter | Implemented Value | Code Reference |
|---|---|---|
| Sample Rate | 16,000 Hz (16 kHz) | `KeywordScanner.SAMPLE_RATE` |
| Channel Configuration | Mono (`CHANNEL_IN_MONO`) | `KeywordScanner.channelConfig` |
| Audio Encoding | PCM 16-bit signed integer | `KeywordScanner.audioEncoding` |
| Analysis Window | 2.0 seconds (32,000 samples) | `KeywordScanner.LIVE_WINDOW_MS` |
| Hop Interval | 800 ms (12,800 samples) | `KeywordScanner.LIVE_HOP_MS` |
| VAD Frame Size | 20 ms (320 samples) | `KeywordScanner.FRAME_SIZE_MS` |
| Frame Energy Floor | 0.012f (~ -38 dBFS) | `KeywordScanner.FRAME_ENERGY_THRESHOLD` |
| Minimum Window RMS | 0.018f | `KeywordScanner.MIN_SPEECH_RMS` |
| Minimum Window Peak | 0.040f | `KeywordScanner.MIN_SPEECH_PEAK` |
| Minimum Active Speech | 500 ms within 2.0s window | `KeywordScanner.MIN_SPEECH_DURATION_MS` |
| Network Concurrency | Single in-flight request guard | `VoiceIdClient.isRequestInFlight` |

---

## Voice Detection & Risk Engine

CallGuard evaluates security state through a deterministic, explainable multi-signal engine (`CallGuardRiskEngine.kt`):

```
                       +-------------------+
                       | Call Active?      |
                       +---------+---------+
                                 |
                       +---------+---------+
                       | YES               | NO
                       v                   v
             +-------------------+     [GREEN] Safe
             | Payment App Open? |     (No alert)
             +---------+---------+
                       |
             +---------+---------+
             | YES               | NO
             v                   v
     [Risk Evaluation]         [GREEN] Monitoring
             |                 (No alert)
             +-----------------------------------------+
             |                                         |
             v                                         v
   Synthetic Voice Confirmed?                No Confirmed Clone?
   (2 consecutive windows)                             |
             |                                         v
             v                                     [ORANGE]
           [RED]                          Contextual Risk Warning
   High-Priority Alert                    * Credential requests
   * Red warning banner                   * Coercive urgency
   * Hindi & English text                 * Financial context
   * 10s dismiss cooldown                 (Keywords alone NEVER
   * One-click 1930 dialer                 trigger RED)
```

### Risk Engine Truth Table

| Call State | Payment App Foreground | Audio / Keyword Signal | Risk Level | Alert Explanation |
|---|---|---|---|---|
| No call | No payment app | Any | `GREEN` | System secure — No active call, no financial app |
| No call | Payment app open | Any | `GREEN` | Normal payment usage — no active phone call |
| Active call | No payment app | Any | `GREEN` | Active call in progress — Monitoring background audio |
| Active call | Payment app open | Genuine voice / No keywords | `ORANGE` | Active call + payment app open — High-risk financial context |
| Active call | Payment app open | Credential keywords (OTP, PIN) | `ORANGE` | Credential request detected — Do not share passwords or PINs |
| Active call | Payment app open | Urgency keywords (Jaldi, Arrest) | `ORANGE` | Urgency detected — Verify caller identity |
| Active call | Payment app open | Credential + Urgency keywords | `ORANGE` | Urgent credential warning — Take a moment before transferring |
| Active call | Payment app open | **Confirmed Synthetic Voice** | `RED` | Possible cloned voice detected during payment call |

### Monitored Financial Applications
CallGuard monitors package visibility transitions for major Indian payment applications:
- Google Pay (`com.google.android.apps.nbu.paisa.user`)
- PhonePe (`com.phonepe.app`)
- Paytm (`net.one97.paytm`)
- BHIM UPI (`in.org.npci.upiapp`)
- Amazon Pay (`com.amazon.mShop.android.shopping`)

### Call Detection Sources
Call state is evaluated through dual telephony and audio checks:
1. Cellular SIM calls via `TelephonyManager.CALL_STATE_OFFHOOK`.
2. VoIP calls (WhatsApp, Telegram, Signal, Google Meet) via `AudioManager.MODE_IN_COMMUNICATION` or `AudioManager.MODE_IN_CALL`.

---

## ML & Anti-Spoofing Pipeline

Backend inference is executed by `backend/app/main.py`:

### 1. Inbound Audio Validation
Uploaded audio is converted to 32-bit floating point PCM at native sampling rate. Input is validated against:
- Numerical finiteness (`np.all(np.isfinite(waveform))`)
- Minimum duration: $\ge 0.5\text{ seconds}$ (`MIN_DURATION_S = 0.5`)
- Root-Mean-Square energy: $\ge 0.018$ (`ENERGY_RMS_THRESHOLD = 0.018`)
- Peak amplitude: $\ge 0.040$ (`PEAK_THRESHOLD = 0.040`)

Audio failing any threshold returns `UNCERTAIN` ($P=0.50$, uncertainty $\sigma=0.30$) to prevent silence or room reverberation from producing false alarms.

### 2. Model Ensemble
- **RawNet2** (`backend/app/rawnet2_model.py`): Processes raw time-domain waveforms directly using learned Sinc-convolutions and residual blocks. Trained on raw audio to detect high-frequency synthesis boundaries and vocoder artifacts without time-frequency spectrogram loss.
- **ECAPA-TDNN**: Utilizes Squeeze-and-Excitation Res2Net blocks to extract speaker embeddings, comparing temporal consistency across segmented halves of the audio buffer via cosine similarity.

### 3. Score Fusion & Decision Boundaries
Individual raw model scores are fused using fixed multi-model weights:

$$P_{\text{combined}} = 0.75 \cdot P_{\text{RawNet2}} + 0.25 \cdot P_{\text{ECAPA}}$$

The combined score is mapped to discrete output verdicts:
- $P_{\text{combined}} \ge 0.60 \implies \text{SYNTHETIC}$ (Confidence = $P_{\text{combined}}$)
- $P_{\text{combined}} \le 0.40 \implies \text{GENUINE}$ (Confidence = $1.0 - P_{\text{combined}}$)
- $0.40 < P_{\text{combined}} < 0.60 \implies \text{UNCERTAIN}$ (Confidence = $0.50$)

### 4. Calibration Parameters
Score scaling uses an identity configuration ($T = 1.0, \beta = 0.0$) mapping log-odds:

$$P = \frac{1}{1 + \exp\left(-\frac{\text{logit} - \beta}{T}\right)}$$

Operational parameters currently preserve identity mapping ($T=1.0, \beta=0.0$); empirical task-specific Platt calibration has not been fitted.

### 5. Graceful Single-Model Degradation
If either model encounters a timeout ($> 2.0\text{s}$) or execution error:
- The system falls back to the surviving single model stream.
- The uncertainty margin expands from $\sigma = 0.05$ to $\sigma = 0.20$.
- If both model streams fail, the endpoint returns `UNCERTAIN` ($P=0.50, \sigma=0.50$).

---

## Session Isolation & Temporal Confirmation

To eliminate transient acoustic spikes and network race conditions, `CallSessionTracker.kt` enforces strict temporal policies:

1. **Monotonic Session Identifiers**: Each active call is assigned a unique timestamp ID (`currentCallSessionId = System.currentTimeMillis()`). Responses from stale or previous call sessions are discarded.
2. **Two-Window Temporal Confirmation**: Live microphone audio strictly requires **two consecutive `SYNTHETIC` windows** (`candidateSyntheticCount >= 2`) before the system escalates from ORANGE to RED.
3. **Immediate Genuine Reset**: Any audio window evaluated as `GENUINE` immediately resets `candidateSyntheticCount` to `0`, clearing candidate alert state.
4. **Uncertain Window Preservation**: An `UNCERTAIN` window preserves the candidate count without incrementing or confirming, preventing environmental dips from dropping valid attack detection.
5. **Network Fail-Safe**: Connection failures return `VoiceIdResult(isSuccess=false, verdict="UNAVAILABLE", confidence=0.0f)`. A network drop immediately clears candidate count and cannot trigger RED.
6. **Call End Invalidation**: Terminating a call immediately resets session tracking, clears candidate counts, and releases microphone resources.

---

## Privacy & Data Handling

- **Zero Cloud Recording**: Audio recorded by the Android client is processed in temporary memory buffers. No caller audio is stored on persistent flash storage.
- **On-Demand Microphone Use**: The microphone is acquired strictly during active telephone calls when accessibility monitoring confirms a financial app is foregrounded or call context is present. The microphone is closed when the call ends.
- **Fail-Safe Connectivity**: If the backend inference server is unreachable, the client degrades safely to ORANGE contextual warnings based on app and call presence alone; it never triggers false RED clone alerts.
- **Explicit Permission Model**: Requires runtime `RECORD_AUDIO`, `READ_PHONE_STATE`, `SYSTEM_ALERT_WINDOW`, and explicit user enablement of `KavachAccessibilityService` in Android Settings.

---

## Platform Limitations & Technical Constraints

1. **Downlink Audio Access**: Standard Android OS security prohibits unprivileged applications from intercepting the downlink voice audio of cellular calls or third-party VoIP apps. Remote caller audio must be played via speakerphone to be captured acoustically by the device microphone.
2. **Advisory Friction vs. OS Blocking**: Third-party Android accessibility services cannot forcibly kill or freeze other applications' processes. The RED overlay operates as high-friction advisory intervention with an emergency dialer; it does not constitute a low-level banking lock.
3. **VoiceArmor HAL Isolation**: The proactive UAP perturbation engine (`VoiceArmorEngine`) is isolated in the consumer user-space build to prevent AudioRecord conflicts on consumer devices. It is provided as an architectural blueprint for OEM DSP integration.
4. **Calibration**: Score calibration parameters currently use identity defaults ($T=1.0, \beta=0.0$); empirical task-specific calibration has not been fitted.
5. **Incident Dossier Generation**: The backend incident dossier builder generates structured PDF evidence matching NCRP reporting conventions; it does not connect to live government intake APIs.

---

## Repository Structure

```
KavachVoice/
├── android/                             # Android Client Application (API 26+)
│   ├── app/
│   │   ├── build.gradle.kts             # Module build configuration (compileSdk 35)
│   │   └── src/
│   │       ├── main/
│   │       │   ├── AndroidManifest.xml  # Permissions, services, and launcher icon bindings
│   │       │   ├── kotlin/com/kavachvoice/
│   │       │   │   ├── MainActivity.kt               # Status UI, permissions, diagnostic dashboard
│   │       │   │   ├── KavachAccessibilityService.kt # Window monitoring & call synchronization
│   │       │   │   ├── KavachForegroundService.kt    # Microphone foreground service holder
│   │       │   │   ├── CallGuardEngine.kt            # WindowManager overlay intervention
│   │       │   │   ├── CallGuardRiskEngine.kt        # Deterministic multi-signal risk logic
│   │       │   │   ├── CallSessionTracker.kt         # Session isolation & temporal confirmation
│   │       │   │   ├── KeywordScanner.kt             # Single-path AudioRecord, rolling buffer, VAD
│   │       │   │   ├── VoiceIdClient.kt              # Async HTTP multipart WAV client
│   │       │   │   ├── VoiceArmorEngine.kt           # Layer 1 UAP prototype (OEM blueprint)
│   │       │   │   └── ServiceRestartReceiver.kt     # Background persistence broadcast receiver
│   │       │   └── res/                              # Layouts, adaptive mipmaps, drawables
│   │       └── test/kotlin/com/kavachvoice/          # Android unit tests (54 tests)
│   │           ├── AudioVadTest.kt                   # VAD and speech activity gate tests
│   │           ├── CallGuardRiskEngineTest.kt        # Truth table & adversarial tests
│   │           └── CallSessionTrackerTest.kt         # Session & temporal confirmation tests
│   └── gradle/libs.versions.toml        # Version catalog (AGP 8.5.2, Kotlin 2.0.0)
├── backend/                             # Anti-Spoofing Inference Service
│   ├── app/
│   │   ├── main.py                      # FastAPI app, RawNet2/ECAPA inference, WebSocket, PDF
│   │   └── rawnet2_model.py             # PyTorch RawNet2 model architecture
│   ├── tests/
│   │   └── test_validation_set.py       # Detection pipeline test harness
│   └── requirements.txt                 # PyTorch 2.4.1, FastAPI 0.115, ReportLab, SoundFile
├── dashboard/                           # Contact Center Console
│   ├── src/
│   │   ├── App.jsx                      # Live alert feed, dropzone, verdict viewer
│   │   └── index.css                    # Tailwind CSS styles
│   ├── vite.config.js                   # Vite dev proxy configuration
│   └── package.json                     # React 18, Tailwind CSS, Vite
├── models/                              # Model Weight Checkpoints
│   ├── rawnet2.pt                       # ASVspoof 2021 RawNet2 baseline weights
│   ├── ecapa_tdnn.pt                    # Pre-trained ECAPA-TDNN weights
│   └── MODELS.md                        # Model acquisition instructions
└── demo/                                # Testing Samples & Assets
    ├── validation_set/                  # Benchmark genuine and synthetic WAV clips
    └── gen_demo_audio.py                # Audio test asset generation script
```

---

## Technology Stack

| Layer | Component | Technology / Library | Version |
|---|---|---|---|
| **Android** | Programming Language | Kotlin | 2.0.0 |
| | Android Gradle Plugin | AGP | 8.5.2 |
| | Android SDK | compileSdk 35, targetSdk 35, minSdk 26 | Android 8.0–15 |
| | Audio Ingestion | Android AudioRecord (16kHz PCM-16) | Native AOSP |
| | UI & Overlay | Native WindowManager (`TYPE_ACCESSIBILITY_OVERLAY`) | Native AOSP |
| | Asynchronous Concurrency | Kotlin Coroutines | Default |
| **Backend** | API Framework | FastAPI / Uvicorn | 0.115.0 / 0.30.6 |
| | Deep Learning Engine | PyTorch / Torchaudio | 2.4.1 |
| | Audio DSP | SoundFile / Librosa / NumPy / SciPy | 0.12.1 / 0.10.2 / 1.26.4 |
| | Evidence Generation | ReportLab | 4.2.2 |
| | Real-Time Messaging | WebSockets / aiofiles | 13.1 / 24.1.0 |
| **Dashboard** | Frontend Framework | React 18 | 18.3.1 |
| | Build Tooling | Vite | 5.4.4 |
| | CSS Framework | Tailwind CSS | 3.4.10 |

---

## Installation & Setup

### Prerequisites
- **Android Development**: Android Studio Ladybug (2024.2+) or newer, JDK 17, Android SDK API 35.
- **Backend Service**: Python 3.10 to 3.12, PyTorch-compatible CPU or CUDA GPU.
- **Dashboard**: Node.js 18+ and npm 9+.
- **Physical Device**: Android smartphone running Android 10+ (API 29+) with USB debugging enabled.

---

### 1. Backend Setup

```bash
# Navigate to backend directory
cd backend

# Create and activate virtual environment
# Windows (PowerShell):
python -m venv venv
.\venv\Scripts\Activate.ps1
# Linux/macOS:
python3 -m venv venv
source venv/bin/activate

# Install dependencies
pip install -r requirements.txt

# Start FastAPI server
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

- API Health Check: `http://localhost:8000/health`
- Interactive OpenAPI Docs: `http://localhost:8000/docs`

---

### 2. Dashboard Setup

```bash
# Navigate to dashboard directory
cd dashboard

# Install dependencies
npm install

# Start development server
npm run dev
```

- Console URL: `http://localhost:5173` (requests to `/api` and `/ws` automatically proxy to `localhost:8000`).

---

### 3. Android Client Setup

1. Open the `android/` directory in Android Studio.
2. Allow Gradle sync to resolve dependencies from `gradle/libs.versions.toml`.
3. Configure your local backend address:
   - If testing over USB: run `adb reverse tcp:8000 tcp:8000` (app defaults to `http://127.0.0.1:8000`).
   - If testing over local Wi-Fi: configure your workstation's LAN IP in `VoiceIdClient.DEFAULT_BACKEND_URL` or via the Developer Settings in the app.
4. Build and install the debug APK:
   ```bash
   cd android
   .\gradlew.bat assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
5. On the Android device:
   - Open **KavachVoice**.
   - Grant **Microphone** (`RECORD_AUDIO`) and **Phone** (`READ_PHONE_STATE`) permissions.
   - Tap **Enable CallGuard** to navigate to **Accessibility Settings** and enable **KavachVoice Protection**.
   - Grant **Display over other apps** (`SYSTEM_ALERT_WINDOW`) if prompted.

---

## API Reference

The backend exposes the following REST and WebSocket endpoints:

| Method | Endpoint | Description | Request Payload | Response |
|---|---|---|---|---|
| `GET` | `/health` | Service health & model state | None | `{"status":"ok","rawnet2":true,"ecapa":true}` |
| `POST` | `/api/v1/analyze` | Audio anti-spoofing analysis | Multipart form with `file` (WAV) | `AnalysisResult` JSON |
| `GET` | `/api/v1/report/{session_id}` | Download PDF forensic dossier | Path parameter `session_id` | PDF file (`application/pdf`) |
| `POST` | `/api/v1/dossier/generate` | Generate structured incident dossier | JSON payload (`DossierRequest`) | Dossier metadata & download URL |
| `GET` | `/api/v1/dossier/download/{file}`| Download generated dossier file | Path parameter `file` | PDF file (`application/pdf`) |
| `WS` | `/ws/alerts` | Real-time verdict WebSocket stream | WebSocket upgrade | Pushes `AnalysisResult` JSON |

### Sample Analysis Response

```json
{
  "session_id": "8f3b6c20-a472-4d1d-9f2e-1e9a3b2e5f10",
  "verdict": "SYNTHETIC",
  "confidence": 0.9421,
  "calibrated_score": 0.9421,
  "uncertainty_sigma": 0.05,
  "rawnet2_score": 0.9612,
  "ecapa_score": 0.8848,
  "rawnet2_calibrated": 0.9612,
  "ecapa_calibrated": 0.8848,
  "latency_ms": 312.4,
  "audio_sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}
```

---

## Testing

The project includes unit test suites covering the audio gate, risk engine truth table, and session isolation.

### Android Unit Tests

Run via the Gradle wrapper from the `android/` directory:

```bash
cd android
.\gradlew.bat test
```

The test suite contains 54 automated unit tests:

1. **`AudioVadTest` (4 tests)**:
   - Validates silence buffer rejection ($RMS < 0.018$, active speech = $0\text{ms}$).
   - Validates low-noise environmental audio rejection ($Peak < 0.040$).
   - Validates synthetic human speech wave acceptance ($RMS \ge 0.018$, $Peak \ge 0.040$, duration $\ge 500\text{ms}$).
   - Confirms active speech duration accumulation algorithm.

2. **`CallGuardRiskEngineTest` (24 tests)**:
   - Validates the 8 core truth table states (safe cases, monitoring cases, contextual ORANGE cases, and synthetic voice RED cases).
   - Confirms that credential keywords (`otp`, `pin`, `password`) and urgency terms (`jaldi`, `police`, `arrest`) alone produce ORANGE warnings and **never** trigger RED.
   - Validates keyword token categorization across Hindi and English vocabularies.
   - Evaluates adversarial false-positive scenarios (benign casual phrases like *"bhai UPI kar de"*, shouting, whispering, room noise, fan noise, keyboard clicks).

3. **`CallSessionTrackerTest` (26 tests)**:
   - Validates session isolation and rejection of stale/mismatched session IDs.
   - Verifies the two-window temporal confirmation requirement (`candidateSyntheticCount == 2`).
   - Verifies that a single `GENUINE` window immediately resets candidate counts.
   - Verifies that `UNCERTAIN` windows fail safe without triggering false positives.
   - Confirms that network dropouts (`UNAVAILABLE`) immediately clear candidate counts.
   - Confirms that reference test asset bypass (`sessionId == 0L`) does not leak into live audio sessions.

### Backend Validation Tests

Run from the `backend/` directory:

```bash
cd backend
pytest tests/test_validation_set.py -v
```

Validates model inference on sample test audio waveforms from `demo/validation_set/`.

---

## Known Limitations

- **Acoustic Coupling Dependency**: Because unprivileged Android applications cannot access the telephony downlink stream directly, remote caller speech can only be evaluated if the victim uses speakerphone or loudspeaker.
- **Advisory Scope**: CallGuard's window overlay provides user friction, advisory warnings, and quick dialer actions; it cannot freeze external banking application processes or prevent a user from manually completing a transfer.
- **Calibration Status**: Score calibration parameters currently use identity defaults ($T=1.0, \beta=0.0$); empirical task-specific calibration has not been fitted.
- **Incident Reporting Integration**: The backend incident dossier builder generates structured PDF evidence matching NCRP reporting conventions; it does not connect to live government intake APIs.

---

## License

This project is licensed under the Apache License, Version 2.0. See the `LICENSE` file for details.
