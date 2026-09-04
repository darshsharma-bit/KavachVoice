import os
import sys
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BACKEND_DIR))

from fastapi.testclient import TestClient
from app.main import app, _rawnet2, _ecapa

client = TestClient(app)

print("=== QUESTION 2: MODEL WEIGHTS STATUS ===")
print(f"_rawnet2 is None: {_rawnet2 is None}")
print(f"_ecapa is None:   {_ecapa is None}")
h = client.get("/health").json()
print(f"Health check response: {h}")

print("\n=== QUESTION 1: SYNTHETIC VS GENUINE COMPARISON ===")
for label, filename in [
    ("SYNTHETIC (clone)", "clone_raw.wav"),
    ("GENUINE (real)", "genuine_raw.wav"),
]:
    audio_path = BACKEND_DIR.parent / "demo" / filename
    with open(audio_path, "rb") as f:
        r = client.post("/api/v1/analyze", files={"file": (filename, f, "audio/wav")})
    data = r.json()
    print(f"{label}: verdict={data['verdict']}, confidence={data['confidence']}, "
          f"rawnet2_score={data.get('rawnet2_score')}, ecapa_score={data.get('ecapa_score')}, "
          f"latency_ms={data.get('latency_ms')}")

print("\n=== QUESTION 3: DOSSIER GENERATE & DOWNLOAD ===")
dossier_payload = {
    "call_id": "SIH-2026-TEST-001",
    "verdict": "SYNTHETIC",
    "confidence": 0.8749,
    "confidence_margin": 0.05,
    "audio_hash_sha256": "fc59c3499e152b00795bdbb2eacefc8ac9d1239a55b56312e9b8e8fca8b5bbc2",
    "duration_s": 4.0,
    "stream_a_score": 0.8134,
    "stream_b_score": 0.95,
    "risk_tier": "RED",
    "explanation": "Harmonic spectral coupling consistent with neural vocoder synthesis."
}
r_gen = client.post("/api/v1/dossier/generate", json=dossier_payload)
print(f"POST /api/v1/dossier/generate status: {r_gen.status_code}")
print(f"Response: {r_gen.json()}")

pdf_fn = r_gen.json().get("pdf_filename")
r_dl = client.get(f"/api/v1/dossier/download/{pdf_fn}")
print(f"GET /api/v1/dossier/download/{pdf_fn} status: {r_dl.status_code}, length: {len(r_dl.content)} bytes")
