import { useState, useEffect, useRef } from 'react'

const VERDICT_STYLE = {
  SYNTHETIC: 'bg-red-600 text-white',
  GENUINE:   'bg-green-600 text-white',
  UNCERTAIN: 'bg-amber-500 text-white',
}

function VerdictCard({ result, onI4CSubmit }) {
  const style = VERDICT_STYLE[result.verdict] ?? 'bg-gray-500 text-white'
  return (
    <div className={`rounded-xl p-6 ${style} mb-4 shadow-lg transition-all`}>
      <div className="flex items-center justify-between mb-2">
        <span className="text-2xl font-bold">{result.verdict}</span>
        <span className="text-lg">{(result.confidence * 100).toFixed(1)}% confidence</span>
      </div>
      <div className="text-sm opacity-90 space-y-1">
        <div>RawNet2 / LFCC: {(result.rawnet2_score * 100).toFixed(1)}%  |  ECAPA-TDNN: {(result.ecapa_score * 100).toFixed(1)}%</div>
        <div>Latency: {result.latency_ms.toFixed(0)} ms  |  Session: {result.session_id.slice(0, 8)}…</div>
        <div className="font-mono text-xs break-all opacity-75">SHA-256: {result.audio_sha256.slice(0, 32)}…</div>
      </div>
      <div className="mt-4 flex flex-wrap gap-2">
        <a
          href={`/api/v1/report/${result.session_id}`}
          className="px-4 py-2 bg-white/20 hover:bg-white/30 rounded-lg text-sm font-medium transition-colors"
          target="_blank" rel="noreferrer"
        >
          📄 Download Section 65B Forensic Report (PDF)
        </a>
        <button
          onClick={() => onI4CSubmit(result)}
          className="px-4 py-2 bg-white/15 hover:bg-white/25 rounded-lg text-sm font-medium transition-colors cursor-pointer"
        >
          ⚖️ Submit to I4C / CERT-In [DEMO MODE]
        </button>
      </div>
    </div>
  )
}

export default function App() {
  const [file, setFile] = useState(null)
  const [loading, setLoading] = useState(false)
  const [results, setResults] = useState([])
  const [wsStatus, setWsStatus] = useState('connecting')
  const [i4cModal, setI4cModal] = useState(null)
  const wsRef = useRef(null)

  useEffect(() => {
    function connect() {
      const wsProto = location.protocol === 'https:' ? 'wss:' : 'ws:'
      const wsHost = location.port === '5173' ? 'localhost:8000' : location.host
      const ws = new WebSocket(`${wsProto}//${wsHost}/ws/alerts`)
      wsRef.current = ws
      ws.onopen  = () => setWsStatus('connected')
      ws.onclose = () => { setWsStatus('disconnected'); setTimeout(connect, 2000) }
      ws.onerror = () => ws.close()
      ws.onmessage = (e) => {
        const data = JSON.parse(e.data)
        setResults(prev => [data, ...prev].slice(0, 20))
      }
    }
    connect()
    return () => wsRef.current?.close()
  }, [])

  async function analyzeFile(targetFile) {
    const fileToUpload = targetFile || file
    if (!fileToUpload) return
    setLoading(true)
    try {
      const form = new FormData()
      form.append('file', fileToUpload)
      const res = await fetch('/api/v1/analyze', { method: 'POST', body: form })
      const data = await res.json()
      setResults(prev => [data, ...prev].slice(0, 20))
    } catch (e) {
      console.error('Analysis failed:', e)
    } finally {
      setLoading(false)
    }
  }

  function handleQuickDemo(type) {
    // Generate synthetic mock audio blob for 1-click stage test
    const sr = 16000
    const duration = 2
    const numSamples = sr * duration
    const buffer = new ArrayBuffer(44 + numSamples * 2)
    const view = new DataView(buffer)

    // Minimal valid WAV header
    const writeString = (offset, str) => {
      for (let i = 0; i < str.length; i++) view.setUint8(offset + i, str.charCodeAt(i))
    }
    writeString(0, 'RIFF')
    view.setUint32(4, 36 + numSamples * 2, true)
    writeString(8, 'WAVE')
    writeString(12, 'fmt ')
    view.setUint32(16, 16, true)
    view.setUint16(20, 1, true) // PCM
    view.setUint16(22, 1, true) // Mono
    view.setUint32(24, sr, true)
    view.setUint32(28, sr * 2, true)
    view.setUint16(32, 2, true)
    view.setUint16(34, 16, true)
    writeString(36, 'data')
    view.setUint32(40, numSamples * 2, true)

    // Audio samples: higher frequency tone for clone, gentle for real
    const freq = type === 'clone' ? 880 : 220
    for (let i = 0; i < numSamples; i++) {
      const sample = Math.sin((2 * Math.PI * freq * i) / sr) * 0.5 * 32767
      view.setInt16(44 + i * 2, sample, true)
    }

    const filename = type === 'clone' ? 'clone_synthetic_demo.wav' : 'genuine_human_voice.wav'
    const blob = new Blob([buffer], { type: 'audio/wav' })
    const demoFile = new File([blob], filename, { type: 'audio/wav' })
    setFile(demoFile)
    analyzeFile(demoFile)
  }

  return (
    <div className="min-h-screen bg-gray-950 text-gray-100 p-6">
      <header className="mb-8 flex items-center justify-between border-b border-gray-800 pb-4">
        <div>
          <h1 className="text-3xl font-bold text-blue-400 tracking-tight">KavachVoice</h1>
          <p className="text-gray-400 text-sm">Layer 3: Enterprise VoiceID SDK  |  SBI Protocol FR-7</p>
        </div>
        <div className="text-right">
          <span className="inline-flex items-center gap-2 px-3 py-1 bg-gray-900 rounded-full border border-gray-800 text-xs">
            <span className={`w-2 h-2 rounded-full ${wsStatus === 'connected' ? 'bg-green-400 animate-pulse' : 'bg-red-400'}`}></span>
            WebSocket: <span className={wsStatus === 'connected' ? 'text-green-400 font-semibold' : 'text-red-400'}>{wsStatus}</span>
          </span>
        </div>
      </header>

      <div className="max-w-3xl">
        <div className="bg-gray-900 rounded-xl p-6 mb-6 border border-gray-800 shadow-xl">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-lg font-semibold text-gray-200">Analyze Inbound Audio Stream</h2>
            <div className="flex gap-2">
              <button
                onClick={() => handleQuickDemo('clone')}
                className="px-3 py-1.5 bg-red-950 hover:bg-red-900 text-red-300 border border-red-800 rounded-lg text-xs font-medium transition-colors cursor-pointer"
              >
                ⚡ 1-Click: Deepfake Voice (Demo 4)
              </button>
              <button
                onClick={() => handleQuickDemo('genuine')}
                className="px-3 py-1.5 bg-green-950 hover:bg-green-900 text-green-300 border border-green-800 rounded-lg text-xs font-medium transition-colors cursor-pointer"
              >
                🛡️ 1-Click: Genuine Voice
              </button>
            </div>
          </div>

          <input
            type="file"
            accept="audio/*"
            className="block w-full text-sm text-gray-400 mb-4 file:mr-4 file:py-2.5 file:px-4 file:rounded-lg file:border-0 file:bg-blue-600 file:text-white hover:file:bg-blue-700 cursor-pointer"
            onChange={e => setFile(e.target.files[0])}
          />

          <button
            onClick={() => analyzeFile()}
            disabled={!file || loading}
            className="w-full py-2.5 bg-blue-600 hover:bg-blue-700 disabled:opacity-40 rounded-lg font-medium transition-colors cursor-pointer shadow-md"
          >
            {loading ? 'Evaluating Dual-Stream Ensemble (RawNet2 + ECAPA-TDNN)…' : 'Run Enterprise Anti-Spoofing Analysis'}
          </button>
        </div>

        <div>
          <h2 className="text-lg font-semibold mb-3 text-gray-200">
            Real-Time Analysis Feed {results.length > 0 && `(${results.length})`}
          </h2>
          {results.length === 0 && (
            <div className="p-8 text-center bg-gray-900/50 rounded-xl border border-gray-800 text-gray-500 text-sm">
              No audio analyzed yet. Click the 1-Click Demo buttons above or upload a WAV file.
            </div>
          )}
          {results.map(r => (
            <VerdictCard
              key={r.session_id}
              result={r}
              onI4CSubmit={res => setI4cModal(res)}
            />
          ))}
        </div>
      </div>

      {/* I4C Submission Modal */}
      {i4cModal && (
        <div className="fixed inset-0 bg-black/75 backdrop-blur-sm flex items-center justify-center p-4 z-50">
          <div className="bg-gray-900 border border-gray-700 rounded-2xl max-w-md w-full p-6 shadow-2xl">
            <div className="flex items-center gap-3 mb-4">
              <span className="text-3xl">⚖️</span>
              <div>
                <h3 className="text-lg font-bold text-white">I4C Forensic Incident Dossier</h3>
                <p className="text-xs text-gray-400">National Cyber Crime Reporting Portal (cybercrime.gov.in)</p>
              </div>
            </div>

            <div className="bg-gray-950 p-4 rounded-xl border border-gray-800 space-y-2 mb-4 text-xs font-mono text-gray-300">
              <div><span className="text-gray-500">Dossier ID:</span> CERT-IN-2026-04471</div>
              <div><span className="text-gray-500">Session Ref:</span> {i4cModal.session_id}</div>
              <div><span className="text-gray-500">Verdict:</span> <span className="text-red-400 font-bold">{i4cModal.verdict}</span> ({Math.round(i4cModal.confidence * 100)}% confidence)</div>
              <div><span className="text-gray-500">Audio SHA-256:</span> {i4cModal.audio_sha256.slice(0, 24)}…</div>
              <div><span className="text-gray-500">Legal Standard:</span> Section 65B Indian Evidence Act / Section 63 BSA</div>
              <div><span className="text-gray-500">Governance:</span> Bank CISO Human-in-the-Loop Authorized</div>
            </div>

            <p className="text-xs text-green-400 mb-6 flex items-center gap-1.5">
              <span>✓</span> Incident evidence sealed with cryptographic timestamp for statutory filing.
            </p>

            <button
              onClick={() => setI4cModal(null)}
              className="w-full py-2 bg-gray-800 hover:bg-gray-700 rounded-lg text-sm font-medium transition-colors cursor-pointer"
            >
              Close Dossier Window
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
