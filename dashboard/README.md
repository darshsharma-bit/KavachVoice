# Layer 3 — VoiceID Dashboard (React + TailwindCSS)

## Setup
```bash
cd dashboard
npm install
npm run dev
```

## Features
- Audio file upload → POST to FastAPI `/api/v1/analyze`
- Real-time alert feed via WebSocket `/ws/alerts`
- GENUINE / SYNTHETIC / UNCERTAIN verdict cards with confidence scores
- PDF forensic report download
- Mock "Submit to I4C" button (labeled DEMO MODE)
