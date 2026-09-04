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
 * impl: per-pattern override of the framework's default style label —
 *   see the comment on FRAMEWORKS below.
 */

/**
 * The `style` here is a DEFAULT, deliberately vague about the DSL.
 *
 * It used to read "Declarative — AiServices + Agentic DSL" for every pattern,
 * which was true for two of the five. Claiming the DSL everywhere is the one
 * place in this UI where an attentive viewer who opens the repo would catch
 * us overselling.
 *
 * The count moved on 2026-09-03: routing was rewritten from plain AiServices
 * plus a Java switch to conditionalBuilder() over shared state, so THREE of
 * the five patterns now use the Agentic DSL — chaining (sequenceBuilder),
 * routing (conditionalBuilder) and evaluator-optimizer (loopBuilder).
 * Parallelization and orchestrator-workers stay on plain AiServices plus
 * hand-written control flow, each for a documented reason (see the javadoc
 * on the pattern classes): the DSL earns its keep where a pattern has STATE
 * and a CONDITION, and adds nothing where it only has concurrency.
 *
 * Per-pattern truth lives in PATTERNS[].impl and overrides this.
 */
export const FRAMEWORKS = [
  { id: 'lc4j',   name: 'LangChain4j', proxy: '/api/patterns-lc4j',
    style: 'Declarative — AiServices',
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
    // Real Agentic DSL: state flows between sub-agents via outputKey,
    // no hand-written handoff between the links.
    impl: {
      lc4j:   'Agentic DSL — sequenceBuilder + outputKey',
      spring: 'plain Java — three ChatClient calls',
    },
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
    // Agentic DSL: conditionalBuilder() picks the branch from shared state,
    // so the dispatch is a declaration instead of a switch on an enum.
    impl: {
      lc4j:   'Agentic DSL — conditionalBuilder on shared state',
      spring: 'plain Java — switch on an enum',
    },
  },
  {
    id:          'parallel',
    name:        '3. Parallelization',
    description: 'Scores all rumor candidates concurrently, then aggregates.',
    traceHint:   'Tempo: OVERLAPPING chat spans — the waterfall stops being a staircase.',
    method:      'GET',
    path:        '/api/v1/patterns/parallel',
    input:       'none',
    // Identical implementation on BOTH sides, on purpose: the DSL adds
    // nothing that CompletableFuture does not already give, and hiding the
    // executor would hide the mechanics. With the code the same, any
    // difference in the timings comes from the model, not the framework.
    impl: {
      lc4j:   'plain Java — CompletableFuture (DSL adds nothing here)',
      spring: 'plain Java — CompletableFuture',
    },
  },
  {
    id:          'evaluator',
    name:        '4. Evaluator-optimizer',
    description: 'Generator proposes a squad once, then a scorer/fixer loop improves it until score ≥ 0.85.',
    traceHint:   'Tempo: one chat, then N repetitions of a score+fix pair.',
    method:      'GET',
    path:        '/api/v1/patterns/evaluator',
    input:       'season',
    defaultValue: 2007,
    // The sharpest DSL contrast in the set: the stop condition is a
    // declaration on the shared state, not a place in the control flow.
    //
    // The 0.85 in the description is not arbitrary — at 0.8 the scoring
    // rules grant exactly 0.8 for a correct first draft, so the loop exited
    // after one pass and the pattern demonstrated nothing. Keep this number
    // in sync with THRESHOLD in BOTH EvaluatorOptimizerPattern classes and
    // with the flow diagram.
    impl: {
      lc4j:   'Agentic DSL — loopBuilder + exitCondition',
      spring: 'plain Java — for loop + if (score >= threshold)',
    },
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
    // TWO READINGS OF THE SAME PATTERN, and the timings are NOT comparable:
    // LC4j lets one agent plan inside a single conversation (~2 LLM calls),
    // Spring AI makes the three phases explicit (~10 calls). Both are valid
    // orchestrator-workers; they answer different questions. supervisorBuilder()
    // would be the DSL route on the LC4j side — deliberately not used, so
    // that the "model plans in-conversation" variant is visible at all.
    impl: {
      lc4j:   'AiServices — one agent, tools, model plans in-conversation',
      spring: 'plain Java — explicit planner → workers → synthesis',
    },
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
