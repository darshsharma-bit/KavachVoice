# Layer 3 — VoiceID SDK (Python FastAPI)

## Setup
```bash
cd backend
python -m venv venv
venv\Scripts\activate      # Windows
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

## API Contract

### POST /api/v1/analyze
Upload audio file for spoofing detection.

**Response:**
```json
{
  "verdict": "GENUINE" | "SYNTHETIC" | "UNCERTAIN",
  "confidence": 0.0–1.0,
  "rawnet2_score": float,
  "ecapa_score": float,
  "latency_ms": int,
  "session_id": "uuid",
  "i4c_format": { ... }
}
```

### GET /health
Returns `{"status": "ok"}` with model load state.

### WS /ws/alerts
WebSocket stream — pushes verdict JSON on each analysis.

### GET /api/v1/report/{session_id}
Download PDF forensic report (SHA-256 audio hash, spectral plot, IST timestamp, I4C fields).
