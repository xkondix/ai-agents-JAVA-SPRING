import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowLeft, Play, Loader2, Workflow } from 'lucide-react'
import { PATTERNS, FRAMEWORKS, LANGUAGES, runPattern } from '../patterns.js'

/**
 * Patterns Lab — runs each workflow pattern against BOTH mirror modules
 * (patterns-langchain4j :8087, patterns-spring-ai :8088) in parallel and
 * shows the answers side by side with execution times.
 *
 * This page IS the thesis of the talk: same pattern, two frameworks,
 * one click — compare the code, the answer, the latency and the trace.
 */
function PatternCard({ pattern }) {
  const [value, setValue] = useState(pattern.defaultValue ?? '')
  const [language, setLanguage] = useState(LANGUAGES[0]) // English default
  const [running, setRunning] = useState(false)
  const [results, setResults] = useState(null) // { lc4j: {...}, spring: {...} }

  const runBoth = async () => {
    setRunning(true)
    setResults(null)
    const [lc4j, spring] = await Promise.all(
      FRAMEWORKS.map(fw => runPattern(fw, pattern, value, language))
    )
    setResults({ lc4j, spring })
    setRunning(false)
  }

  return (
    <div className="rounded-2xl border border-slate-800 bg-slate-900/50 p-6">
      {/* header */}
      <div className="flex items-start justify-between gap-3 mb-3">
        <div>
          <p className="font-bold text-slate-100 text-xl">{pattern.name}</p>
          <p className="text-base text-slate-300 mt-1.5 leading-relaxed">
            {pattern.description}
          </p>
        </div>
      </div>

      {/* input */}
      <div className="flex items-center gap-3 mt-4">
        {pattern.input === 'season' && (
          <input
            type="number"
            value={value}
            onChange={e => setValue(e.target.value)}
            className="w-32 px-4 py-2.5 rounded-lg bg-slate-800 border border-slate-700
                       text-base text-slate-200 focus:outline-none focus:border-indigo-500"
            placeholder="season"
          />
        )}
        {pattern.input === 'text' && (
          <input
            type="text"
            value={value}
            onChange={e => setValue(e.target.value)}
            className="flex-1 px-4 py-2.5 rounded-lg bg-slate-800 border border-slate-700
                       text-base text-slate-200 focus:outline-none focus:border-indigo-500"
            placeholder="task / question"
          />
        )}
        {pattern.language && (
          <select
            value={language}
            onChange={e => setLanguage(e.target.value)}
            title="Target language of the final report"
            className="px-4 py-2.5 rounded-lg bg-slate-800 border border-slate-700
                       text-base text-slate-200 focus:outline-none focus:border-indigo-500
                       cursor-pointer"
          >
            {LANGUAGES.map(lang => (
              <option key={lang} value={lang}>{lang}</option>
            ))}
          </select>
        )}
        <button
          onClick={runBoth}
          disabled={running}
          className="flex items-center gap-2 px-5 py-2.5 rounded-lg bg-indigo-600
                     hover:bg-indigo-500 disabled:opacity-50 text-white text-base
                     font-semibold transition-colors shrink-0"
        >
          {running ? <Loader2 size={16} className="animate-spin" /> : <Play size={16} />}
          Run both
        </button>
      </div>

      {/* results side by side */}
      {results && (
        <div className="grid grid-cols-2 gap-4 mt-5">
          {FRAMEWORKS.map(fw => {
            const r = results[fw.id]
            return (
              <div key={fw.id}
                   className={`rounded-xl border ${fw.borderClass} border-opacity-40
                               bg-slate-950 p-4 min-w-0`}>
                <div className="flex items-center justify-between mb-2.5">
                  <span className={`text-base font-bold ${fw.textClass}`}>{fw.name}</span>
                  <span className={`text-sm font-mono px-2 py-0.5 rounded
                                    ${r.ok ? 'bg-slate-800 text-slate-300'
                                           : 'bg-red-900/50 text-red-400'}`}>
                    {r.ok ? `${r.ms} ms` : `ERROR · ${r.ms} ms`}
                  </span>
                </div>
                <p className="text-base text-slate-200 whitespace-pre-wrap break-words
                              max-h-72 overflow-y-auto leading-relaxed">
                  {r.body || '(empty response)'}
                </p>
              </div>
            )
          })}
        </div>
      )}

      {/* trace hint */}
      <p className="text-sm text-slate-400 mt-4">{pattern.traceHint}</p>
    </div>
  )
}

export default function PatternsPage() {
  return (
    <div className="h-screen overflow-y-auto bg-slate-950">
      <div className="max-w-5xl mx-auto px-6 py-8">

        {/* header */}
        <div className="flex items-center gap-3 mb-2">
          <Link to="/" className="p-2 rounded-lg bg-slate-800 hover:bg-slate-700
                                  text-slate-400 transition-colors">
            <ArrowLeft size={18} />
          </Link>
          <div className="w-10 h-10 rounded-lg bg-indigo-600 flex items-center justify-center">
            <Workflow size={20} className="text-white" />
          </div>
          <div>
            <h1 className="font-bold text-slate-100 text-xl">Patterns Lab</h1>
            <p className="text-base text-slate-400">
              Same pattern · two frameworks · one click —{' '}
              <span className="text-indigo-400">LangChain4j :8087</span> vs{' '}
              <span className="text-emerald-400">Spring AI :8088</span>
            </p>
          </div>
        </div>

        <p className="text-sm text-slate-500 mb-6 ml-[3.75rem]">
          Both modules must be running. Every run lands in Grafana Tempo —
          recognize the pattern by its waterfall shape.
        </p>

        {/* cards */}
        <div className="space-y-5">
          {PATTERNS.map(p => <PatternCard key={p.id} pattern={p} />)}
        </div>
      </div>
    </div>
  )
}
