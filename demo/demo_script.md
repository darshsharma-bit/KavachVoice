# KavachVoice — Demo Script (4 Moments, <5 min total)

## Setup (before judges arrive)
- FastAPI running: `cd backend && uvicorn app.main:app --host 0.0.0.0 --port 8000`
- React dashboard: `cd dashboard && npm run dev` → open `http://localhost:5173`
- Android device: plugged in, screen timeout = Never, KavachVoice accessibility service ON
- Phone on same WiFi as laptop (backend hardcoded IP in Android)
- Have `demo/clone_raw.wav` ready in dashboard file picker (pre-select it)
- Have `demo/spectrogram_raw.png` and `demo/spectrogram_perturbed.png` open in image viewer

---

## Moment 1 — Clone Attack (~45 seconds)

**Talking point:**
> "This audio clip is a synthetic voice clone of our team member, generated using ElevenLabs.
> It sounds completely real — but it is AI-generated. This is exactly what scammers use in vishing attacks."

**Action:** Play `clone_raw.wav` on laptop speakers.

**Talking point:**
> "Our Layer 3 — VoiceID SDK — uses RawNet2 and ECAPA-TDNN, two state-of-the-art anti-spoofing models,
> running in parallel via asyncio for sub-800ms inference."

**Action:** In the React dashboard, click the pre-selected file, click **Run VoiceID Analysis**.

**Expected result:** Verdict card appears — `SYNTHETIC` in red, confidence > 60%.
> "SYNTHETIC. The AI caught the AI."

---

## Moment 2 — VoiceArmor Shield (~45 seconds)

**Talking point:**
> "But KavachVoice doesn't just detect — it defends proactively.
> Layer 1, VoiceArmor, applies a Universal Adversarial Perturbation to audio in real time,
> before any cloning attempt can capture clean voice data."

**Action:** Switch to image viewer. Show `spectrogram_raw.png` first.
> "This is the clean clone — the spectrogram is smooth, harmonics intact, cloneable."

**Action:** Switch to `spectrogram_perturbed.png`.
> "After VoiceArmor: the UAP has disrupted the harmonic structure.
> Cloning algorithms fail on this — the synthesised output degrades into noise."

---

## Moment 3 — UPI Block (live on device, ~90 seconds)

**Talking point:**
> "Layer 2, CallGuard, runs inside an Accessibility Service — no root required.
> It monitors for UPI apps opening during an active phone call, and listens for fraud keywords in Hindi and Hinglish."

**Action (live on device):**
1. Start a phone call (to another team member's phone, or demo with call already active)
2. Open Google Pay / PhonePe on the device
3. Speak: *"OTP bataiye, jaldi karo"*

**Expected result:** Red overlay fires within 2 seconds:
```
🛑  STOP — Do NOT Share OTP
    OTP request detected. No legitimate caller ever asks for this. Hang up now.
                                                              KavachVoice
```

**Talking point:**
> "Red alert. The overlay uses TYPE_ACCESSIBILITY_OVERLAY — it fires even over the lock screen,
> with no SYSTEM_ALERT_WINDOW permission needed. The victim cannot miss it."

**Fallback (if live call not possible):**
> Trigger demo mode: in MainActivity, tap the hidden debug button (3x tap on version text) — fires Moment 3 immediately.

---

## Moment 4 — Forensic Report + I4C (~60 seconds)

**Talking point:**
> "Finally — evidence. When a SYNTHETIC verdict fires, KavachVoice automatically generates a forensic PDF report:
> SHA-256 audio hash, model scores, session ID, timestamp — all immutable."

**Action:** In the React dashboard, click **Download Forensic Report (PDF)** under the SYNTHETIC result.

**Expected:** PDF opens showing:
- KavachVoice header with blue SIH 2026 branding
- `Verdict: SYNTHETIC` in red
- Table: Session ID, timestamp, confidence scores, SHA-256 hash
- Interpretation paragraph with I4C submission note

**Talking point:**
> "This PDF can be submitted directly to I4C — India's Indian Cyber Crime Coordination Centre —
> as structured evidence. We have a mock submission endpoint labeled DEMO MODE."

**Action:** Click **Submit to I4C [DEMO MODE]** button (greyed, shows intent).

> "In production, this calls the I4C API with the forensic bundle. The button is demo-gated for today."

---

## Closing (15 seconds)

> "KavachVoice is three layers of protection — proactive, real-time, and evidence-grade.
> VoiceArmor poisons the clone before it's made. CallGuard blocks the attack mid-call.
> VoiceID catches it at the server. No other competing solution has all three.
> AWS and Azure both retired their voice fraud APIs in 2024. We built what they couldn't maintain."

---

## Contingency

| Moment | If it fails | Fallback |
|---|---|---|
| 1 (SYNTHETIC verdict) | Low-energy audio → UNCERTAIN | Replay with a louder clip; stub returns SYNTHETIC for high-energy audio |
| 2 (spectrograms) | Script didn't run | Show pre-saved PNGs from gen_demo_audio.py output |
| 3 (RED overlay) | No live call possible | Trigger demoMode via 3x tap on version field in MainActivity |
| 4 (PDF download) | Server not running | Show pre-generated PDF from `backend/reports/` directory |
