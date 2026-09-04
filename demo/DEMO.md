Place demo assets here. Not committed to git (listed in .gitignore).

Expected:
- clone_raw.wav          — ElevenLabs/Coqui synthetic clone of consenting team member (Moment 1 & 4)
- clone_perturbed.wav    — Same clip after VoiceArmor UAP applied (Moment 2)
- spectrogram_raw.png    — Spectrogram of clone_raw.wav
- spectrogram_perturbed.png — Spectrogram of clone_perturbed.wav (shows degradation)
- demo_script.md         — Talking points for each of the 4 demo moments

Demo flow (target <5 minutes total):
1. Clone Attack — play clone_raw.wav, show "this is AI-generated"
2. Shield — play through VoiceArmor, show spectrogram diff
3. UPI Block — live on device: call + open GPay + speak "OTP bataiye" → Red overlay
4. SDK Catch — upload clone_raw.wav to dashboard → SYNTHETIC verdict → download PDF
