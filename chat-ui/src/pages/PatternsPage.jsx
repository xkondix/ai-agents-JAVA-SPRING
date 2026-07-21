import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowLeft, Play, Loader2, Workflow } from 'lucide-react'
import { PATTERNS, FRAMEWORKS, LANGUAGES, LANGUAGE_LABELS, runPattern } from '../patterns.js'
import PatternDiagram, { DiagramLegend } from '../components/PatternDiagram.jsx'

/**
 * Patterns Lab — runs each workflow pattern against BOTH mirror modules
 * (patterns-langchain4j :8087, patterns-spring-ai :8088) in parallel and
 * shows the answers side by side with execution times.
 *
 * Layout: ONE card per pattern.
 *   top row : description + controls (left)  |  flow diagram (right)
 *             both aligned to the TOP of the card
 *   bottom  : framework results, FULL card width — the side-by-side
 *             comparison is the point of this page, so it gets the space.
 *
 * The legend sits in the page header, right-aligned so it lands directly
 * above the column of flow diagrams it explains.
 *
 * Colour convention: indigo/emerald identify the FRAMEWORKS (result
 * panels); the flows use a separate palette for node ROLES
 * (see DiagramLegend) because a pattern is framework-agnostic.
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

      {/* ── top row: text + controls | flow (top-aligned) ──────────── */}
      <div className="grid grid-cols-1 lg:grid-cols-[minmax(0,1fr)_460px] gap-6 items-start">

        <div>
          <p className="font-bold text-slate-100 text-2xl">{pattern.name}</p>
          <p className="text-lg text-slate-300 mt-2 leading-relaxed">
            {pattern.description}
          </p>

          <div className="flex items-center gap-3 mt-5 flex-wrap">
            {pattern.input === 'season' && (
              <input
                type="number"
                value={value}
                onChange={e => setValue(e.target.value)}
                className="w-32 px-4 py-3 rounded-lg bg-slate-800 border border-slate-700
                           text-lg text-slate-200 focus:outline-none focus:border-indigo-500"
                placeholder="season"
              />
            )}
            {pattern.input === 'text' && (
              <input
                type="text"
                value={value}
                onChange={e => setValue(e.target.value)}
                className="flex-1 min-w-[240px] px-4 py-3 rounded-lg bg-slate-800
                           border border-slate-700 text-lg text-slate-200
                           focus:outline-none focus:border-indigo-500"
                placeholder="task / question"
              />
            )}
            {pattern.language && (
              <select
                value={language}
                onChange={e => setLanguage(e.target.value)}
                title="Target language of the final report ('Mixed' blends them all)"
                className="px-4 py-3 rounded-lg bg-slate-800 border border-slate-700
                           text-lg text-slate-200 focus:outline-none focus:border-indigo-500
                           cursor-pointer"
              >
                {LANGUAGES.map(lang => (
                  <option key={lang} value={lang}>
                    {LANGUAGE_LABELS[lang] || lang}
                  </option>
                ))}
              </select>
            )}
            <button
              onClick={runBoth}
              disabled={running}
              className="flex items-center gap-2 px-6 py-3 rounded-lg bg-indigo-600
                         hover:bg-indigo-500 disabled:opacity-50 text-white text-lg
                         font-semibold transition-colors shrink-0"
            >
              {running ? <Loader2 size={18} className="animate-spin" /> : <Play size={18} />}
              Run both
            </button>
          </div>

          <p className="text-base text-slate-400 mt-4">{pattern.traceHint}</p>
        </div>

        {/* flow diagram */}
        <div className="rounded-xl border border-slate-800 bg-slate-950/40 p-4">
          <p className="text-sm font-semibold text-slate-500 uppercase tracking-wide mb-2">
            Flow
          </p>
          <PatternDiagram patternId={pattern.id} />
        </div>
      </div>

      {/* ── bottom: results across the FULL card width ─────────────── */}
      {results && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 mt-6 pt-6
                        border-t border-slate-800">
          {FRAMEWORKS.map(fw => {
            const r = results[fw.id]
            return (
              <div key={fw.id}
                   className={`rounded-xl border ${fw.borderClass} border-opacity-40
                               bg-slate-950 p-4 min-w-0`}>
                <div className="flex items-center justify-between mb-3">
                  <div className="flex items-baseline gap-2 min-w-0">
                    <span className={`text-lg font-bold ${fw.textClass}`}>{fw.name}</span>
                    <span className="text-sm text-slate-500 truncate">{fw.style}</span>
                  </div>
                  <span className={`text-base font-mono px-2 py-0.5 rounded shrink-0
                                    ${r.ok ? 'bg-slate-800 text-slate-300'
                                           : 'bg-red-900/50 text-red-400'}`}>
                    {r.ok ? `${r.ms} ms` : `ERROR · ${r.ms} ms`}
                  </span>
                </div>
                <p className="text-lg text-slate-200 whitespace-pre-wrap break-words
                              max-h-96 overflow-y-auto leading-relaxed">
                  {r.body || '(empty response)'}
                </p>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

export default function PatternsPage() {
  return (
    <div className="h-screen overflow-y-auto bg-slate-950">
      <div className="max-w-[1500px] mx-auto px-6 py-8">

        {/* header: title on the left, legend on the right
            (right column ≈ the flow column below it) */}
        <div className="flex items-start justify-between gap-6 flex-wrap mb-6">

          <div>
            <div className="flex items-center gap-3">
              <Link to="/" className="p-2 rounded-lg bg-slate-800 hover:bg-slate-700
                                      text-slate-400 transition-colors">
                <ArrowLeft size={20} />
              </Link>
              <div className="w-11 h-11 rounded-lg bg-indigo-600 flex items-center justify-center">
                <Workflow size={22} className="text-white" />
              </div>
              <div>
                <h1 className="font-bold text-slate-100 text-2xl">Patterns Lab</h1>
                <p className="text-lg text-slate-400">
                  Same pattern · two frameworks · one click —{' '}
                  <span className="text-indigo-400">LangChain4j :8087</span> vs{' '}
                  <span className="text-emerald-400">Spring AI :8088</span>
                </p>
              </div>
            </div>
            <p className="text-base text-slate-500 mt-3 ml-[4rem]">
              Both modules must be running. Every run lands in Grafana Tempo —
              recognize the pattern by its waterfall shape.
            </p>
          </div>

          {/* colour key — sits above the column of flow diagrams */}
          <div className="rounded-xl border border-slate-800 bg-slate-900/40 px-5 py-3
                          w-full lg:w-[460px] shrink-0">
            <p className="text-sm font-semibold text-slate-500 uppercase tracking-wide mb-2">
              Legend
            </p>
            <DiagramLegend />
          </div>
        </div>

        {/* one card per pattern */}
        <div className="space-y-6">
          {PATTERNS.map(p => <PatternCard key={p.id} pattern={p} />)}
        </div>
      </div>
    </div>
  )
}
