/**
 * Patterns Lab configuration.
 *
 * One entry per workflow pattern (NOT per framework) — the Lab runs each
 * pattern against BOTH mirror modules and shows the results side by side:
 *   patterns-langchain4j → port 8087 (proxy /api/patterns-lc4j)
 *   patterns-spring-ai   → port 8088 (proxy /api/patterns-spring)
 *
 * input:
 *   'season' → number field sent as ?season=
 *   'text'   → textarea sent as raw request body (POST)
 *   'none'   → just a Run button
 * language: true → additionally shows a target-language dropdown,
 *   sent as &language= (backend default: English)
 */
export const FRAMEWORKS = [
  { id: 'lc4j',   name: 'LangChain4j', proxy: '/api/patterns-lc4j',
    style: 'Declarative — AiServices + Agentic DSL',
    textClass: 'text-indigo-400', borderClass: 'border-indigo-500' },
  { id: 'spring', name: 'Spring AI',   proxy: '/api/patterns-spring',
    style: 'Explicit — ChatClient + plain Java',
    textClass: 'text-emerald-400', borderClass: 'border-emerald-500' },
]

/**
 * Values are sent to the API verbatim — keep in sync with
 * common/src/main/java/com/xkondix/common/lang/TranslationLanguages.java
 *
 * 'Mixed' is not a language: the backend turns it into an instruction to
 * blend ALL supported languages in one text (a word or two from each).
 */
export const LANGUAGES = [
  'English',   // default
  'Polish',
  'Romanian',
  'Hindi',
  'Dutch',
  'Greek',
  'Turkish',
  'Mixed',     // all of the above blended together
]

/** Nicer label for the dropdown; value stays exactly as the API expects. */
export const LANGUAGE_LABELS = {
  Mixed: 'Mixed (all languages)',
}

export const PATTERNS = [
  {
    id:          'chain',
    name:        '1. Prompt chaining',
    description: 'Scout analysis → condensed takeaways → report translated into the chosen language. Output of step N feeds step N+1.',
    traceHint:   'Tempo: a staircase of sequential "chat" spans.',
    method:      'GET',
    path:        '/api/v1/patterns/chain',
    input:       'season',
    defaultValue: 2007,
    language:    true,
  },
  {
    id:          'routing',
    name:        '2. Routing',
    description: 'A cheap classifier picks the specialist: squad / transfers / rumors 🔒. The rumors branch calls a tool gated by human approval.',
    traceHint:   'Tempo: one SHORT chat (router) + one LONG chat (specialist). Ask for rumors and the tool span waits for you in /approvals.',
    method:      'POST',
    path:        '/api/v1/patterns/routing',
    // Imperative phrasing on purpose: "do you know any rumors?" is a
    // CAPABILITY question and models answer it with an offer instead of
    // calling the tool.
    input:       'text',
    defaultValue: 'Show me the latest AC Milan transfer rumors.',
  },
  {
    id:          'parallel',
    name:        '3. Parallelization',
    description: 'Scores all rumor candidates concurrently, then aggregates.',
    traceHint:   'Tempo: OVERLAPPING chat spans — the waterfall stops being a staircase.',
    method:      'GET',
    path:        '/api/v1/patterns/parallel',
    input:       'none',
  },
  {
    id:          'evaluator',
    name:        '4. Evaluator-optimizer',
    description: 'Generator proposes a squad, evaluator scores it; feedback loops until score ≥ 0.8.',
    traceHint:   'Tempo: N repetitions of a chat+chat pair.',
    method:      'GET',
    path:        '/api/v1/patterns/evaluator',
    input:       'season',
    defaultValue: 2007,
  },
  {
    id:          'orchestrator',
    name:        '5. Orchestrator-workers',
    description: 'Central LLM plans subtasks at runtime and delegates to tool workers.',
    traceHint:   'Tempo: an irregular tool sequence you did not know in advance.',
    method:      'POST',
    path:        '/api/v1/patterns/orchestrator',
    input:       'text',
    defaultValue: 'Compare the 2007 and 2024 squads and pick the stronger midfield.',
  },
]

/** Runs one pattern against one framework; resolves to {ok, body, ms}. */
export async function runPattern(framework, pattern, value, language) {
  const started = performance.now()
  try {
    let url = framework.proxy + pattern.path
    const params = new URLSearchParams()
    const options = { method: pattern.method }
    if (pattern.input === 'season') {
      params.set('season', value)
    } else if (pattern.input === 'text') {
      options.headers = { 'Content-Type': 'text/plain' }
      options.body = value
    }
    if (pattern.language && language) {
      params.set('language', language)
    }
    const query = params.toString()
    if (query) url += `?${query}`

    const res = await fetch(url, options)
    const body = await res.text()
    return { ok: res.ok, body, ms: Math.round(performance.now() - started) }
  } catch (e) {
    return { ok: false, body: String(e), ms: Math.round(performance.now() - started) }
  }
}
