import os
import sys
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BACKEND_DIR))

from fastapi.testclient import TestClient
import app.main as main_mod
from app.main import app

VALIDATION_DIR = BACKEND_DIR.parent / "demo" / "validation_set"

test_samples = [
    # Genuine samples (5)
    ("genuine_01_librispeech.wav", "GENUINE"),
    ("genuine_02_dataset.wav", "GENUINE"),
    ("genuine_03_dataset.wav", "GENUINE"),
    ("genuine_04_dataset.wav", "GENUINE"),
    ("genuine_05_hindi_speech.wav", "GENUINE"),
    # Synthetic samples (5)
    ("synthetic_01_clone_raw.wav", "SYNTHETIC"),
    ("synthetic_02_clone.wav", "SYNTHETIC"),
    ("synthetic_03_clone.wav", "SYNTHETIC"),
    ("synthetic_04_clone.wav", "SYNTHETIC"),
    ("synthetic_05_uap_perturbed.wav", "SYNTHETIC"),
]

with TestClient(app) as client:
    print("=========================================================================================")
    print("KAVACHVOICE VOICEID VALIDATION HARNESS — REAL PRE-TRAINED NEURAL MODELS (RAWNET2 + ECAPA)")
    print("=========================================================================================")
    
    h = client.get("/health").json()
    print(f"Health Check: status={h.get('status')}, RawNet2={h.get('rawnet2')}, ECAPA={h.get('ecapa')}\n")

    results = []
    correct_count = 0

    print(f"{'Sample Name':<32} | {'True Label':<9} | {'Verdict':<9} | {'Conf':<6} | {'RawNet2':<7} | {'ECAPA':<7} | {'Latency':<7}")
    print("-" * 92)

    for filename, true_label in test_samples:
        file_path = VALIDATION_DIR / filename
        if not file_path.exists():
            print(f"ERROR: {file_path} not found!")
            continue

        with open(file_path, "rb") as f:
            r = client.post("/api/v1/analyze", files={"file": (filename, f, "audio/wav")})
        data = r.json()
        
        verdict = data.get("verdict")
        conf = data.get("confidence", 0.0)
        rn2 = data.get("rawnet2_score", 0.0)
        ecapa = data.get("ecapa_score", 0.0)
        lat = data.get("latency_ms", 0.0)

        is_correct = (verdict == true_label)
        if is_correct:
            correct_count += 1

        results.append({
            "sample": filename,
            "true_label": true_label,
            "verdict": verdict,
            "confidence": conf,
            "rawnet2": rn2,
            "ecapa": ecapa,
            "latency_ms": lat,
            "correct": is_correct
        })

        print(f"{filename:<32} | {true_label:<9} | {verdict:<9} | {conf*100:>5.1f}% | {rn2*100:>6.1f}% | {ecapa*100:>6.1f}% | {lat:>5.1f}ms")

    print("-" * 92)
    acc = (correct_count / len(test_samples)) * 100
    print(f"Total Evaluated: {len(test_samples)} | Correct: {correct_count} | Overall Accuracy: {acc:.1f}%\n")

    misclassified = [r for r in results if not r["correct"]]
    if misclassified:
        print("Flagged Misclassifications / Ambiguities:")
        for m in misclassified:
            print(f"  - {m['sample']}: Expected {m['true_label']}, got {m['verdict']} (Confidence: {m['confidence']*100:.1f}%)")
    else:
        print("Zero Misclassifications: 100% agreement across all 10 evaluation pairs.")
