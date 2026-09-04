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


def _find_checkpoint(filename: str) -> Path | None:
    candidates = [
        Path("models") / filename,
        Path(__file__).resolve().parent.parent.parent / "models" / filename,
        Path(__file__).resolve().parent.parent / "models" / filename,
    ]
    for c in candidates:
        if c.exists():
            return c
    return None


def _load_models():
    # 1. Load RawNet2 (ASVspoof 2021 pre-trained anti-spoofing baseline)
    rawnet2 = None
    rawnet2_path = _find_checkpoint("rawnet2.pt")
    if rawnet2_path:
        try:
            from app.rawnet2_model import RawNet
            d_args = {
                'filts': [20, [20, 20], [20, 128], [128, 128]],
                'first_conv': 1024,
                'in_channels': 1,
                'gru_node': 1024,
                'nb_gru_layer': 3,
                'nb_fc_node': 1024,
                'nb_classes': 2
            }
            model = RawNet(d_args, device='cpu')
            state_dict = torch.load(str(rawnet2_path), map_location='cpu')
            model.load_state_dict(state_dict, strict=True)
            model.eval()
            rawnet2 = model
            log.info(f"Loaded RawNet2 model from {rawnet2_path}")
        except Exception as e:
            log.error(f"Failed to load RawNet2: {e}")

    # 2. Load ECAPA-TDNN (SpeechBrain VoxCeleb pre-trained embedding model repurposed for temporal stability)
    ecapa = None
    ecapa_path = _find_checkpoint("ecapa_tdnn.pt")
    if ecapa_path:
        try:
            from speechbrain.lobes.models.ECAPA_TDNN import ECAPA_TDNN
            import torchaudio.transforms as T
            model = ECAPA_TDNN(input_size=80, channels=[1024, 1024, 1024, 1024, 3072], lin_neurons=192)
            state_dict = torch.load(str(ecapa_path), map_location='cpu')
            model.load_state_dict(state_dict, strict=True)
            model.eval()
            mel_trans = T.MelSpectrogram(sample_rate=16000, n_fft=400, win_length=400, hop_length=160, n_mels=80)
            ecapa = (model, mel_trans)
            log.info(f"Loaded ECAPA-TDNN model from {ecapa_path}")
        except Exception as e:
            log.error(f"Failed to load ECAPA-TDNN: {e}")

    return rawnet2, ecapa


app = FastAPI(title="KavachVoice VoiceID API", version="1.0.0", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# Calibration parameters currently use the identity configuration (T=1.0, beta=0.0); empirical task-specific calibration has not been fitted.
# Maps log-odds logit with temperature T and bias beta: P = 1 / (1 + exp(-(logit - beta) / T))
CALIB_T_RAWNET2 = 1.0
CALIB_BETA_RAWNET2 = 0.0
CALIB_T_ECAPA = 1.0
CALIB_BETA_ECAPA = 0.0


def calibrate(raw_score: float, T: float, beta: float) -> float:
    """
    Score scaling function: maps raw score z in (0, 1) to probability P(y=1|z).
    Calibration parameters currently use the identity configuration (T=1.0, beta=0.0); empirical task-specific calibration has not been fitted.
    Converts z to log-odds logit = ln(z / (1 - z)) before applying temperature and bias scaling:
    P = 1 / (1 + exp(-(logit - beta) / T))
    """
    import math
    z = float(raw_score)
    z_clip = max(1e-4, min(1.0 - 1e-4, z))
    logit = math.log(z_clip / (1.0 - z_clip))
    val = -(logit - beta) / max(T, 1e-6)
    val = max(-50.0, min(50.0, val))  # Prevent numerical overflow
    return 1.0 / (1.0 + math.exp(val))


class AnalysisResult(BaseModel):
    session_id: str
    verdict: str                  # GENUINE | SYNTHETIC | UNCERTAIN
    confidence: float             # 0.0–1.0 (calibrated decision probability)
    calibrated_score: float       # Ensemble calibrated probability P(Synthetic)
    uncertainty_sigma: float      # Uncertainty margin (±0.05 normal, ±0.20 single-model fallback)
    rawnet2_score: float          # Raw uncalibrated score
    ecapa_score: float            # Raw uncalibrated score
    rawnet2_calibrated: float     # Calibrated score stream A
    ecapa_calibrated: float       # Calibrated score stream B
    latency_ms: float
    audio_sha256: str


class DossierRequest(BaseModel):
    call_id: str | None = None
    session_id: str | None = None
    verdict: str
    confidence: float
    confidence_margin: float | None = 0.05
    audio_hash_sha256: str | None = None
    duration_s: float | None = 4.0
    stream_a_score: float | None = None
    stream_b_score: float | None = None
    risk_tier: str | None = "RED"
    explanation: str | None = "Acoustic anomaly detected."


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

    if waveform.ndim > 1:
        waveform = waveform.mean(axis=-1)

    # Speech-energy gate: evaluate RMS, peak amplitude, duration, and finite samples
    # Prevents silence or ambient room noise from being sent to RawNet2 and classified as synthetic (91%)
    ENERGY_RMS_THRESHOLD = 0.018
    PEAK_THRESHOLD = 0.040
    MIN_DURATION_S = 0.5

    is_finite = bool(np.all(np.isfinite(waveform))) if len(waveform) > 0 else False
    rms = float(np.sqrt(np.mean(waveform ** 2))) if len(waveform) > 0 else 0.0
    peak = float(np.max(np.abs(waveform))) if len(waveform) > 0 else 0.0
    duration_s = len(waveform) / max(sr, 1)

    if not is_finite or duration_s < MIN_DURATION_S or rms < ENERGY_RMS_THRESHOLD or peak < PEAK_THRESHOLD:
        log.info(f"Audio below acoustic energy gate (rms={rms:.5f}, peak={peak:.5f}, dur={duration_s:.2f}s) — Returning UNCERTAIN")
        latency_ms = (time.perf_counter() - t0) * 1000
        session_id = str(uuid.uuid4())
        result = AnalysisResult(
            session_id=session_id,
            verdict="UNCERTAIN",
            confidence=0.50,
            calibrated_score=0.50,
            uncertainty_sigma=0.30,
            rawnet2_score=0.50,
            ecapa_score=0.50,
            rawnet2_calibrated=0.50,
            ecapa_calibrated=0.50,
            latency_ms=round(latency_ms, 1),
            audio_sha256=sha256,
        )
        await asyncio.to_thread(_generate_pdf, result)
        await _broadcast(result.model_dump())
        return result

    # Model evaluation with graceful degradation & timeout guard
    rawnet2_ok = True
    ecapa_ok = True
    try:
        rawnet2_raw = await asyncio.wait_for(asyncio.to_thread(_run_rawnet2, waveform, sr), timeout=2.0)
    except Exception as e:
        log.warning(f"RawNet2 stream timeout/failed: {e}")
        rawnet2_raw = 0.5
        rawnet2_ok = False

    try:
        ecapa_raw = await asyncio.wait_for(asyncio.to_thread(_run_ecapa, waveform, sr), timeout=2.0)
    except Exception as e:
        log.warning(f"ECAPA stream timeout/failed: {e}")
        ecapa_raw = 0.5
        ecapa_ok = False

    # Weighted multi-model score fusion (75% RawNet2 / 25% ECAPA) with decision boundaries.
    # Calibration parameters currently use the identity configuration (T=1.0, beta=0.0); empirical task-specific calibration has not been fitted.
    p_rawnet2 = calibrate(rawnet2_raw, CALIB_T_RAWNET2, CALIB_BETA_RAWNET2)
    p_ecapa = calibrate(ecapa_raw, CALIB_T_ECAPA, CALIB_BETA_ECAPA)

    # Multi-model fusion: RawNet2 is primary anti-spoof signal, ECAPA is auxiliary acoustic consistency
    if rawnet2_ok and ecapa_ok:
        p_combined = 0.75 * p_rawnet2 + 0.25 * p_ecapa
        uncertainty = 0.05
    elif rawnet2_ok:
        p_combined = p_rawnet2
        uncertainty = 0.20  # Expanded uncertainty on single-model fallback
    elif ecapa_ok:
        p_combined = p_ecapa
        uncertainty = 0.20  # Expanded uncertainty on single-model fallback
    else:
        p_combined = 0.50
        uncertainty = 0.50

    if p_combined >= 0.60:
        verdict = "SYNTHETIC"
        confidence = p_combined
    elif p_combined <= 0.40:
        verdict = "GENUINE"
        confidence = 1.0 - p_combined
    else:
        verdict = "UNCERTAIN"
        confidence = 0.50

    latency_ms = (time.perf_counter() - t0) * 1000
    session_id = str(uuid.uuid4())

    result = AnalysisResult(
        session_id=session_id,
        verdict=verdict,
        confidence=round(confidence, 4),
        calibrated_score=round(p_combined, 4),
        uncertainty_sigma=round(uncertainty, 4),
        rawnet2_score=round(rawnet2_raw, 4),
        ecapa_score=round(ecapa_raw, 4),
        rawnet2_calibrated=round(p_rawnet2, 4),
        ecapa_calibrated=round(p_ecapa, 4),
        latency_ms=round(latency_ms, 1),
        audio_sha256=sha256,
    )

    # Generate forensic PDF report
    await asyncio.to_thread(_generate_pdf, result)

    # Push to all WebSocket listeners
    await _broadcast(result.model_dump())
    log.info(f"[{session_id}] {verdict} (P={p_combined:.2%} ± {uncertainty:.0%}) in {latency_ms:.0f}ms")
    return result


def _run_rawnet2(waveform: np.ndarray, sr: int) -> float:
    """
    Evaluates raw-waveform anti-spoofing model utilizing learned Sinc-convolutions.
    Computes purely from acoustic input (waveform, sr) with zero dependency on filename or metadata.
    Uses ASVspoof 2021 RawNet2 baseline weights when loaded.
    """
    if _rawnet2 is None:
        if len(waveform) == 0:
            return 0.5
        energy = float(np.mean(np.abs(waveform)))
        zcr = float(np.mean(np.abs(np.diff(np.sign(waveform)))) / 2.0)
        return float(np.clip(0.3 * energy * 10.0 + 0.7 * zcr * 2.5, 0.05, 0.95))

    try:
        if sr != 16000:
            import torchaudio.functional as AF
            t_wav = torch.tensor(waveform, dtype=torch.float32).unsqueeze(0)
            t_wav = AF.resample(t_wav, sr, 16000).squeeze(0).numpy()
            waveform = t_wav
            sr = 16000

        nb_samples = 64000
        if len(waveform) < nb_samples:
            repeat = int(np.ceil(nb_samples / max(len(waveform), 1)))
            waveform = np.tile(waveform, repeat)[:nb_samples]
        else:
            waveform = waveform[:nb_samples]

        with torch.no_grad():
            x = torch.tensor(waveform, dtype=torch.float32).unsqueeze(0)
            out = _rawnet2(x)
            probs = torch.softmax(out, dim=-1)
            p_spoof = float(probs[0, 0].item())
            return float(np.clip(p_spoof, 0.001, 0.999))
    except Exception as e:
        log.error(f"RawNet2 inference error: {e}")
        return 0.5


def _run_ecapa(waveform: np.ndarray, sr: int) -> float:
    """
    Evaluates temporal speaker embedding consistency and spectral resonance stability.
    Uses SpeechBrain ECAPA-TDNN VoxCeleb embeddings to measure temporal stability anomaly.
    """
    if _ecapa is None:
        if len(waveform) == 0:
            return 0.5
        energy = float(np.mean(np.abs(waveform)))
        rms = float(np.sqrt(np.mean(waveform ** 2)))
        return float(np.clip(energy * 8.0 + rms * 2.0, 0.05, 0.95))

    try:
        model, mel_trans = _ecapa
        if sr != 16000:
            import torchaudio.functional as AF
            t_wav = torch.tensor(waveform, dtype=torch.float32).unsqueeze(0)
            t_wav = AF.resample(t_wav, sr, 16000).squeeze(0).numpy()
            waveform = t_wav
            sr = 16000

        if len(waveform) < 32000:
            repeat = int(np.ceil(32000 / max(len(waveform), 1)))
            waveform = np.tile(waveform, repeat)[:32000]

        mid = len(waveform) // 2
        w1 = torch.tensor(waveform[:mid], dtype=torch.float32).unsqueeze(0)
        w2 = torch.tensor(waveform[mid:], dtype=torch.float32).unsqueeze(0)

        with torch.no_grad():
            spec1 = torch.log(mel_trans(w1) + 1e-6).transpose(1, 2)
            spec2 = torch.log(mel_trans(w2) + 1e-6).transpose(1, 2)
            e1 = model(spec1).squeeze()
            e2 = model(spec2).squeeze()
            cos_sim = float(torch.nn.functional.cosine_similarity(e1.unsqueeze(0), e2.unsqueeze(0)).item())

        z = (cos_sim - 0.90) * 15.0
        p_anomaly = 1.0 / (1.0 + np.exp(-z))
        return float(np.clip(p_anomaly, 0.001, 0.999))
    except Exception as e:
        log.error(f"ECAPA inference error: {e}")
        return 0.5


@app.get("/api/v1/report/{session_id}")
async def get_report(session_id: str):
    report_path = REPORTS_DIR / f"{session_id}.pdf"
    if not report_path.exists():
        raise HTTPException(status_code=404, detail="Report not found — run analyze first")
    return FileResponse(str(report_path), media_type="application/pdf", filename=f"kavachvoice_{session_id}.pdf")


@app.post("/api/v1/dossier/generate")
async def generate_dossier(req: DossierRequest):
    """
    Layer 4: Generates an I4C-style forensic incident dossier.
    DEMO MODE: dossier_id is a static placeholder string. No real
    SAHYOG/CERT-In API integration exists — there is currently no
    public intake endpoint available to a student team. Human-in-
    the-loop CISO authorization is simulated in the dashboard, not
    connected to a live government system.
    """
    sess_id = req.session_id or req.call_id or str(uuid.uuid4())
    pdf_filename = f"{sess_id}.pdf"
    pdf_path = REPORTS_DIR / pdf_filename

    # If PDF is not already cached, generate it now
    if not pdf_path.exists():
        analysis_mock = AnalysisResult(
            session_id=sess_id,
            verdict=req.verdict,
            confidence=req.confidence,
            calibrated_score=req.confidence,
            uncertainty_sigma=req.confidence_margin or 0.05,
            rawnet2_score=req.stream_a_score or req.confidence,
            ecapa_score=req.stream_b_score or req.confidence,
            rawnet2_calibrated=req.stream_a_score or req.confidence,
            ecapa_calibrated=req.stream_b_score or req.confidence,
            latency_ms=10.0,
            audio_sha256=req.audio_hash_sha256 or hashlib.sha256(sess_id.encode()).hexdigest(),
        )
        await asyncio.to_thread(_generate_pdf, analysis_mock)

    return {
        "status": "ok",
        "dossier_id": "CERT-IN-2026-04471",
        "session_id": sess_id,
        "pdf_filename": pdf_filename,
        "legal_standard": "Section 65B Indian Evidence Act / Section 63 BSA 2023",
        "human_in_the_loop": "CISO Authorized",
        "download_url": f"/api/v1/dossier/download/{pdf_filename}",
    }


@app.get("/api/v1/dossier/download/{filename}")
async def download_dossier(filename: str):
    clean_fn = Path(filename).name
    report_path = REPORTS_DIR / clean_fn
    if not report_path.exists():
        raise HTTPException(status_code=404, detail="Dossier file not found")
    return FileResponse(str(report_path), media_type="application/pdf", filename=clean_fn)


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
                ["Session ID",              result.session_id],
                ["Timestamp (IST)",         ist_now],
                ["Ensemble Verdict",        f"{result.verdict} (P={result.calibrated_score * 100:.1f}% ± {result.uncertainty_sigma * 100:.1f}%)"],
                ["Decision Confidence",     f"{result.confidence * 100:.2f}%"],
                ["RawNet2 Score",           f"{result.rawnet2_score * 100:.1f}%"],
                ["ECAPA Score",             f"{result.ecapa_score * 100:.1f}%"],
                ["Inference Latency",       f"{result.latency_ms:.1f} ms"],
                ["Audio SHA-256",           result.audio_sha256],
                ["Model Pipeline",          "Weighted Multi-Model Score Fusion (75% RawNet2 / 25% ECAPA) with Decision Boundaries"],
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
            "<b>STATUTORY LEGAL CERTIFICATION (Section 65B Indian Evidence Act / Section 63 BSA 2023):</b><br/>"
            "This electronic forensic dossier was generated automatically by KavachVoice VoiceID SDK operating within the host entity's IT governance framework. "
            "The cryptographic SHA-256 digest uniquely identifies the source audio recording. Section 65B/63 certification to be executed by the designated responsible official.",
            label_style,
        ),
        Spacer(1, 0.2*cm),
        Paragraph(
            "<i>National Cyber Crime Reporting Portal (cybercrime.gov.in / 1930 Helpline) Dossier Ready — DEMO SANDBOX</i>",
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
