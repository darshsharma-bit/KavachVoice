import { useState, useEffect, useRef } from 'react'

const VERDICT_STYLE = {
  SYNTHETIC: 'bg-red-600 text-white',
  GENUINE:   'bg-green-600 text-white',
  UNCERTAIN: 'bg-amber-500 text-white',
}

function VerdictCard({ result }) {
  const style = VERDICT_STYLE[result.verdict] ?? 'bg-gray-500 text-white'
  return (
    <div className={`rounded-xl p-6 ${style} mb-4 shadow-lg`}>
      <div className="flex items-center justify-between mb-2">
        <span className="text-2xl font-bold">{result.verdict}</span>
        <span className="text-lg">{(result.confidence * 100).toFixed(1)}% confidence</span>
      </div>
      <div className="text-sm opacity-90 space-y-1">
        <div>RawNet2: {(result.rawnet2_score * 100).toFixed(1)}%  |  ECAPA-TDNN: {(result.ecapa_score * 100).toFixed(1)}%</div>
        <div>Latency: {result.latency_ms.toFixed(0)} ms  |  Session: {result.session_id.slice(0, 8)}…</div>
        <div className="font-mono text-xs break-all opacity-75">SHA-256: {result.audio_sha256.slice(0, 32)}…</div>
      </div>
      <a
        href={`/api/v1/report/${result.session_id}`}
        className="mt-3 inline-block px-4 py-2 bg-white/20 hover:bg-white/30 rounded-lg text-sm font-medium"
        target="_blank" rel="noreferrer"
      >
        Download Forensic Report (PDF)
      </a>
      <button className="mt-3 ml-2 px-4 py-2 bg-white/10 rounded-lg text-sm opacity-60 cursor-default">
        Submit to I4C [DEMO MODE]
      </button>
    </div>
  )
}

export default function App() {
  const [file, setFile] = useState(null)
  const [loading, setLoading] = useState(false)
  const [results, setResults] = useState([])
  const [wsStatus, setWsStatus] = useState('connecting')
  const wsRef = useRef(null)

  useEffect(() => {
    function connect() {
      const ws = new WebSocket(`ws://${location.host}/ws/alerts`)
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

  async function analyze() {
    if (!file) return
    setLoading(true)
    try {
      const form = new FormData()
      form.append('file', file)
      const res = await fetch('/api/v1/analyze', { method: 'POST', body: form })
      const data = await res.json()
      setResults(prev => [data, ...prev].slice(0, 20))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-gray-950 text-gray-100 p-6">
      <header className="mb-8">
        <h1 className="text-3xl font-bold text-blue-400">KavachVoice</h1>
        <p className="text-gray-400 text-sm">Layer 3 — VoiceID SDK  |  WS: <span className={wsStatus === 'connected' ? 'text-green-400' : 'text-red-400'}>{wsStatus}</span></p>
      </header>

      <div className="max-w-2xl">
        <div className="bg-gray-900 rounded-xl p-6 mb-6">
          <h2 className="text-lg font-semibold mb-4">Analyze Audio</h2>
          <input
            type="file"
            accept="audio/*"
            className="block w-full text-sm text-gray-400 mb-4 file:mr-4 file:py-2 file:px-4 file:rounded-lg file:border-0 file:bg-blue-600 file:text-white hover:file:bg-blue-700 cursor-pointer"
            onChange={e => setFile(e.target.files[0])}
          />
          <button
            onClick={analyze}
            disabled={!file || loading}
            className="px-6 py-2 bg-blue-600 hover:bg-blue-700 disabled:opacity-40 rounded-lg font-medium transition-colors"
          >
            {loading ? 'Analyzing…' : 'Run VoiceID Analysis'}
          </button>
        </div>

        <div>
          <h2 className="text-lg font-semibold mb-3">Results {results.length > 0 && `(${results.length})`}</h2>
          {results.length === 0 && (
            <p className="text-gray-500 text-sm">No analyses yet. Upload an audio file above.</p>
          )}
          {results.map(r => <VerdictCard key={r.session_id} result={r} />)}
        </div>
      </div>
    </div>
  )
}
