import asyncio
import hashlib
import logging
import tempfile
import time
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import soundfile as sf
import torch
from fastapi import FastAPI, File, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from pydantic import BaseModel
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import cm
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, HRFlowable

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("kavachvoice")

# Models — loaded once at startup, never per-request
_rawnet2 = None
_ecapa = None
_ws_clients: list[WebSocket] = []


REPORTS_DIR = Path("reports")
REPORTS_DIR.mkdir(exist_ok=True)


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _rawnet2, _ecapa
    log.info("Loading VoiceID models...")
    _rawnet2, _ecapa = await asyncio.to_thread(_load_models)
    log.info(f"Models ready — RawNet2: {'OK' if _rawnet2 else 'STUB'}, ECAPA: {'OK' if _ecapa else 'STUB'}")
    yield
    log.info("Shutting down")


def _load_models():
    rawnet2_path = Path("models/rawnet2.pt")
    ecapa_path = Path("models/ecapa_tdnn.pt")
    rawnet2 = torch.load(rawnet2_path, map_location="cpu") if rawnet2_path.exists() else None
    ecapa = torch.load(ecapa_path, map_location="cpu") if ecapa_path.exists() else None
    if rawnet2:
        rawnet2.eval()
    if ecapa:
        ecapa.eval()
    return rawnet2, ecapa


app = FastAPI(title="KavachVoice VoiceID API", version="1.0.0", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


class AnalysisResult(BaseModel):
    session_id: str
    verdict: str          # GENUINE | SYNTHETIC | UNCERTAIN
    confidence: float     # 0.0–1.0
    rawnet2_score: float
    ecapa_score: float
    latency_ms: float
    audio_sha256: str


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "rawnet2": _rawnet2 is not None,
        "ecapa": _ecapa is not None,
    }


@app.post("/api/v1/analyze", response_model=AnalysisResult)
async def analyze(file: UploadFile = File(...)):
    t0 = time.perf_counter()
    audio_bytes = await file.read()
    sha256 = hashlib.sha256(audio_bytes).hexdigest()

    # Write temp file to read with soundfile
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tf:
        tf.write(audio_bytes)
        tmp = Path(tf.name)
    try:
        waveform, sr = sf.read(str(tmp), dtype="float32")
    finally:
        tmp.unlink(missing_ok=True)

    rawnet2_score, ecapa_score = await asyncio.gather(
        asyncio.to_thread(_run_rawnet2, waveform, sr),
        asyncio.to_thread(_run_ecapa, waveform, sr),
    )

    combined = (rawnet2_score + ecapa_score) / 2.0
    if combined > 0.6:
        verdict, confidence = "SYNTHETIC", combined
    elif combined < 0.4:
        verdict, confidence = "GENUINE", 1.0 - combined
    else:
        verdict, confidence = "UNCERTAIN", 0.5

    latency_ms = (time.perf_counter() - t0) * 1000
    session_id = str(uuid.uuid4())

    result = AnalysisResult(
        session_id=session_id,
        verdict=verdict,
        confidence=round(confidence, 4),
        rawnet2_score=round(rawnet2_score, 4),
        ecapa_score=round(ecapa_score, 4),
        latency_ms=round(latency_ms, 1),
        audio_sha256=sha256,
    )

    # Generate forensic PDF report
    await asyncio.to_thread(_generate_pdf, result)

    # Push to all WebSocket listeners
    await _broadcast(result.model_dump())
    log.info(f"[{session_id}] {verdict} ({confidence:.2%}) in {latency_ms:.0f}ms")
    return result


def _run_rawnet2(waveform: np.ndarray, sr: int) -> float:
    if _rawnet2 is None:
        # Stub: return deterministic score based on audio energy (for demo w/o weights)
        energy = float(np.mean(np.abs(waveform)))
        return min(1.0, energy * 12.0)
    with torch.no_grad():
        x = torch.tensor(waveform).unsqueeze(0)
        score = _rawnet2(x)
        return float(torch.sigmoid(score).item())


def _run_ecapa(waveform: np.ndarray, sr: int) -> float:
    if _ecapa is None:
        energy = float(np.mean(np.abs(waveform)))
        return min(1.0, energy * 10.0)
    with torch.no_grad():
        x = torch.tensor(waveform).unsqueeze(0)
        score = _ecapa(x)
        return float(torch.sigmoid(score).item())


@app.get("/api/v1/report/{session_id}")
async def get_report(session_id: str):
    report_path = REPORTS_DIR / f"{session_id}.pdf"
    if not report_path.exists():
        raise HTTPException(status_code=404, detail="Report not found — run analyze first")
    return FileResponse(str(report_path), media_type="application/pdf", filename=f"kavachvoice_{session_id}.pdf")


@app.websocket("/ws/alerts")
async def ws_alerts(websocket: WebSocket):
    await websocket.accept()
    _ws_clients.append(websocket)
    log.info(f"WS client connected — total: {len(_ws_clients)}")
    try:
        while True:
            await websocket.receive_text()  # keep-alive ping handling
    except WebSocketDisconnect:
        _ws_clients.remove(websocket)
        log.info(f"WS client disconnected — total: {len(_ws_clients)}")


def _generate_pdf(result: "AnalysisResult") -> None:
    path = REPORTS_DIR / f"{result.session_id}.pdf"
    doc = SimpleDocTemplate(str(path), pagesize=A4, topMargin=2*cm, bottomMargin=2*cm)
    styles = getSampleStyleSheet()

    VERDICT_COLOR = {
        "SYNTHETIC": colors.HexColor("#D50000"),
        "GENUINE":   colors.HexColor("#1B5E20"),
        "UNCERTAIN": colors.HexColor("#E65100"),
    }
    vc = VERDICT_COLOR.get(result.verdict, colors.grey)

    title_style = ParagraphStyle("title", parent=styles["Title"], fontSize=20, textColor=colors.HexColor("#1565C0"))
    verdict_style = ParagraphStyle("verdict", parent=styles["Heading1"], fontSize=16, textColor=vc)
    label_style = ParagraphStyle("label", parent=styles["Normal"], fontSize=9, textColor=colors.grey)
    body_style = styles["Normal"]

    ist_now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")

    story = [
        Paragraph("KavachVoice — Forensic Audio Report", title_style),
        Paragraph("Layer 3: VoiceID SDK  |  SIH 2026 — Problem SIH26104", label_style),
        Spacer(1, 0.4*cm),
        HRFlowable(width="100%", thickness=1, color=colors.HexColor("#1565C0")),
        Spacer(1, 0.4*cm),
        Paragraph(f"Verdict: {result.verdict}", verdict_style),
        Spacer(1, 0.3*cm),
        Table(
            [
                ["Field", "Value"],
                ["Session ID",      result.session_id],
                ["Timestamp (IST)", ist_now],
                ["Confidence",      f"{result.confidence * 100:.2f}%"],
                ["RawNet2 Score",   f"{result.rawnet2_score * 100:.2f}%"],
                ["ECAPA-TDNN Score",f"{result.ecapa_score * 100:.2f}%"],
                ["Inference Latency", f"{result.latency_ms:.1f} ms"],
                ["Audio SHA-256",   result.audio_sha256],
                ["Model versions",  "RawNet2 v2.0 | ECAPA-TDNN v1.0 (stub if weights absent)"],
            ],
            colWidths=[5*cm, 12*cm],
            style=TableStyle([
                ("BACKGROUND",  (0, 0), (-1, 0), colors.HexColor("#1565C0")),
                ("TEXTCOLOR",   (0, 0), (-1, 0), colors.white),
                ("FONTNAME",    (0, 0), (-1, 0), "Helvetica-Bold"),
                ("FONTSIZE",    (0, 0), (-1, 0), 10),
                ("BACKGROUND",  (0, 1), (-1, -1), colors.HexColor("#F5F5F5")),
                ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F5F5F5")]),
                ("FONTSIZE",    (0, 1), (-1, -1), 9),
                ("GRID",        (0, 0), (-1, -1), 0.5, colors.HexColor("#CCCCCC")),
                ("VALIGN",      (0, 0), (-1, -1), "MIDDLE"),
                ("TOPPADDING",  (0, 0), (-1, -1), 5),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
                ("WORDWRAP",    (1, -2), (1, -1), "CJK"),
            ]),
        ),
        Spacer(1, 0.6*cm),
        Paragraph("Interpretation", styles["Heading2"]),
        Paragraph(
            "<b>SYNTHETIC</b> — Audio contains statistical signatures consistent with AI voice synthesis. "
            "Do not trust the caller. This report can be submitted to I4C (cybercrime.gov.in) as evidence."
            if result.verdict == "SYNTHETIC" else
            "<b>GENUINE</b> — No synthetic voice markers detected with current model confidence."
            if result.verdict == "GENUINE" else
            "<b>UNCERTAIN</b> — Confidence below threshold. Manual review recommended.",
            body_style,
        ),
        Spacer(1, 0.5*cm),
        HRFlowable(width="100%", thickness=0.5, color=colors.grey),
        Spacer(1, 0.2*cm),
        Paragraph(
            "This report was generated automatically by KavachVoice (SIH 2026, Problem SIH26104). "
            "For legal proceedings, combine with original audio file and this document. "
            "<i>Submit to I4C — DEMO MODE</i>",
            label_style,
        ),
    ]
    doc.build(story)
    log.info(f"PDF report saved: {path}")


async def _broadcast(data: dict):
    dead = []
    for ws in _ws_clients:
        try:
            await ws.send_json(data)
        except Exception:
            dead.append(ws)
    for ws in dead:
        _ws_clients.remove(ws)
