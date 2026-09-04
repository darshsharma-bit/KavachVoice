"""
Block E demo prep — run this script once before the demo.

What it does:
1. If clone_raw.wav is already present (real ElevenLabs/Coqui clip), use it as-is.
   Otherwise, generate a synthetic sine-wave stub so the pipeline is testable.
2. Load uap_profile.bin, apply UAP perturbation to produce clone_perturbed.wav.
3. Render before/after spectrograms as PNG files.

Usage:
    cd demo/
    python gen_demo_audio.py [--real path/to/clone.wav]

Requirements: numpy, soundfile, matplotlib  (all already in backend/requirements.txt)
"""

import argparse
import struct
import sys
from pathlib import Path

import numpy as np

try:
    import soundfile as sf
except ImportError:
    print("soundfile not in PATH — install: pip install soundfile"); sys.exit(1)

try:
    import matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
except ImportError:
    print("matplotlib not in PATH — install: pip install matplotlib"); sys.exit(1)

DEMO_DIR  = Path(__file__).parent
ASSETS    = DEMO_DIR.parent / "android" / "app" / "src" / "main" / "assets"
UAP_BIN   = ASSETS / "uap_profile.bin"
OUT_RAW   = DEMO_DIR / "clone_raw.wav"
OUT_PERT  = DEMO_DIR / "clone_perturbed.wav"
OUT_SPEC_RAW  = DEMO_DIR / "spectrogram_raw.png"
OUT_SPEC_PERT = DEMO_DIR / "spectrogram_perturbed.png"

SR = 16000
DURATION = 4  # seconds


def load_uap() -> np.ndarray:
    if not UAP_BIN.exists():
        print(f"[WARN] UAP profile not found at {UAP_BIN} — using zero perturbation")
        return np.zeros(1024, dtype=np.float32)
    data = UAP_BIN.read_bytes()
    floats = struct.unpack(f"<{len(data)//4}f", data)
    uap = np.array(floats, dtype=np.float32)
    print(f"[INFO] UAP profile loaded: {len(uap)} samples, max abs = {np.abs(uap).max():.6f}")
    return uap


def make_stub_audio(sr: int, duration: int) -> np.ndarray:
    """Harmonic-rich sine burst — resembles voiced speech energy."""
    t = np.linspace(0, duration, sr * duration, endpoint=False, dtype=np.float32)
    fundamental = 120.0  # Hz, typical male/female voice F0
    wave = np.zeros_like(t)
    for harmonic in [1, 2, 3, 5, 8]:
        wave += (1.0 / harmonic) * np.sin(2 * np.pi * fundamental * harmonic * t)
    # Amplitude envelope: attack + decay
    env = np.exp(-0.3 * t) * (1 - np.exp(-5 * t))
    wave = wave * env
    # Normalise to 0.7 peak
    wave = wave / np.abs(wave).max() * 0.7
    return wave.astype(np.float32)


def apply_uap(audio: np.ndarray, uap: np.ndarray) -> np.ndarray:
    indices = np.arange(len(audio)) % len(uap)
    perturbed = audio + uap[indices]
    return np.clip(perturbed, -1.0, 1.0).astype(np.float32)


def save_spectrogram(audio: np.ndarray, sr: int, path: Path, title: str, color: str = "viridis"):
    fig, ax = plt.subplots(figsize=(8, 4))
    ax.specgram(audio, NFFT=512, Fs=sr, noverlap=256, cmap=color)
    ax.set_xlabel("Time (s)")
    ax.set_ylabel("Frequency (Hz)")
    ax.set_title(title, fontsize=12, fontweight="bold")
    ax.set_ylim(0, sr // 2)
    fig.tight_layout()
    fig.savefig(str(path), dpi=150)
    plt.close(fig)
    print(f"[INFO] Spectrogram saved: {path}")


def main():
    parser = argparse.ArgumentParser(description="KavachVoice demo audio prep")
    parser.add_argument("--real", type=Path, default=None,
                        help="Path to a real ElevenLabs/Coqui WAV clone file")
    args = parser.parse_args()

    # Step 1: raw audio
    if args.real and args.real.exists():
        print(f"[INFO] Loading real clone audio: {args.real}")
        audio, sr = sf.read(str(args.real), dtype="float32")
        if audio.ndim > 1:
            audio = audio[:, 0]  # take left channel
        if sr != SR:
            print(f"[WARN] Sample rate {sr} != {SR} — spectrograms may look off")
    else:
        if OUT_RAW.exists():
            print(f"[INFO] Using existing {OUT_RAW}")
            audio, sr = sf.read(str(OUT_RAW), dtype="float32")
        else:
            print("[INFO] Generating stub synthetic clone audio (harmonic sine burst)")
            audio = make_stub_audio(SR, DURATION)
            sf.write(str(OUT_RAW), audio, SR)
            print(f"[INFO] Saved: {OUT_RAW}")

    # Step 2: UAP perturbation
    uap = load_uap()
    perturbed = apply_uap(audio, uap)
    sf.write(str(OUT_PERT), perturbed, SR)
    print(f"[INFO] Perturbed audio saved: {OUT_PERT}")

    delta = np.abs(perturbed - audio[:len(perturbed)]).max()
    print(f"[INFO] Max UAP delta = {delta:.6f}  (stub = near-zero; real UAP ≈ 0.1–0.3)")

    # Step 3: spectrograms
    save_spectrogram(audio[:len(uap)*4], SR, OUT_SPEC_RAW,
                     "Spectrogram — Clone (Unprotected)", color="plasma")
    save_spectrogram(perturbed[:len(uap)*4], SR, OUT_SPEC_PERT,
                     "Spectrogram — VoiceArmor Protected (UAP Applied)", color="viridis")

    print("\n[DONE] Demo assets ready:")
    for p in [OUT_RAW, OUT_PERT, OUT_SPEC_RAW, OUT_SPEC_PERT]:
        size = p.stat().st_size if p.exists() else 0
        print(f"  {p.name:35s}  {size:,} bytes")
    print("\nNext: drop real ElevenLabs/Coqui WAV as clone_raw.wav and re-run to regenerate.")


if __name__ == "__main__":
    main()
