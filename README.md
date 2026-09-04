# KavachVoice

**AI-Powered Real-Time Detection and Prevention of Voice Cloning Impersonation Attacks**

SIH 2026 · Problem SIH26104 · Theme: Blockchain & Cybersecurity · Sponsor: AICTE · Prize: ₹1,00,000

---

## Architecture

KavachVoice is a three-layer defense system against AI voice cloning fraud targeting Indian users.

| Layer | Name | Platform | Role |
|---|---|---|---|
| 1 | VoiceArmor | Android (Kotlin) | Applies adversarial audio perturbation to outgoing mic audio in real-time, making cloned voice outputs unusable |
| 2 | CallGuard | Android (Kotlin) | Detects UPI fraud behavioral patterns — payment app during call, Hindi/Tamil OTP keywords, urgency language |
| 3 | VoiceID SDK | Python + React | Bank-side anti-spoofing API (RawNet2 + ECAPA-TDNN ensemble), real-time dashboard, I4C forensic reports |

Layers 1 and 2 run in a single Android APK, fully offline. Layer 3 runs as a Python FastAPI server.

---

## Repository Structure

```
KavachVoice/
├── android/          # Layer 1 (VoiceArmor) + Layer 2 (CallGuard) — Kotlin Android app
├── backend/          # Layer 3 (VoiceID SDK) — Python FastAPI server
├── dashboard/        # Layer 3 dashboard — React + TailwindCSS
├── models/           # ML model weights (RawNet2, ECAPA-TDNN, UAP profiles)
├── demo/             # Demo assets: clone audio clips, spectrograms, script
└── docs/             # Architecture diagrams, API contract, team notes
```

---

## Four Demo Moments

1. **Clone Attack** — ElevenLabs synthetic voice plays raw (no protection)
2. **Shield** — Same clip through VoiceArmor — perturbation degrades clone output
3. **UPI Block** — Live call + GPay open + Hindi OTP phrase → CallGuard Red alert fires
4. **SDK Catch** — Synthetic audio uploaded to VoiceID dashboard → SYNTHETIC verdict + PDF forensic report

---

## Tech Stack

- **Android:** Kotlin, Accessibility Service, TFLite, AI4Bharat IndicASR lite, min API 29
- **Backend:** Python 3.11, FastAPI, PyTorch (RawNet2 + ECAPA-TDNN), ReportLab
- **Dashboard:** React 18, TailwindCSS, WebSocket
- **Models:** RawNet2 (~2.5% EER on ASVspoof 2021), ECAPA-TDNN (~1.0% EER on VoxCeleb1)

---

## Hackathon Timeline

**Day 1 — Sep 4, 2026:** Coding starts 10:30 AM
**Day 2 — Sep 5, 2026:** Final coding 5:30 AM · PPT submission 8:45 AM · Judging 10:00 AM

---

## Team

[Add teammate names/GitHub handles here]

---

## Problem Statement

India lost ₹11,333 Cr to cyber fraud in 2023. A voice can be cloned in under 30 seconds. As of mid-2026, there is no managed anti-spoofing API available for Indian BFSI (AWS Connect Voice ID retired May 2026, Azure Speaker Recognition retired Sept 2025).

KavachVoice addresses this at three levels: poisoning voice capture before cloning happens (Layer 1), catching fraud behavior patterns in Indian languages (Layer 2), and providing a drop-in detection SDK for bank contact centers (Layer 3).
