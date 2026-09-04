/**
 * Inline SVG flow diagrams for the Patterns Lab — one per pattern,
 * English labels, styled to match the dark slate theme.
 *
 * COLOR SEMANTICS (see <DiagramLegend/>): the palette encodes the ROLE of
 * each node and deliberately avoids indigo/emerald — those two colors are
 * reserved for the frameworks (LangChain4j / Spring AI) in the result
 * panels, and a flow describes a pattern that is common to both.
 *
 *   slate → data in / out (no model involved)
 *   sky   → LLM call
 *   amber → decision / control flow (router, evaluator, orchestrator,
 *           and the human approval gate)
 *
 * All diagrams share a 400-wide viewBox so they render at the same scale in
 * the 460px flow column. The evaluator is the one exception (410) — see the
 * comment there.
 */

const C = {
  box:     '#1e293b', // slate-800
  boxLine: '#64748b', // slate-500
  text:    '#f1f5f9', // slate-100
  sub:     '#94a3b8', // slate-400
  llm:     '#38bdf8', // sky-400   — LLM call
  control: '#fbbf24', // amber-400 — decision / control / human gate
  arrow:   '#94a3b8', // slate-400
}

export const LEGEND = [
  { color: C.boxLine, label: 'Data in / out' },
  { color: C.llm,     label: 'LLM call' },
  { color: C.control, label: 'Decision / control' },
]

/** Colour key — render once per page, not per card. */
export function DiagramLegend() {
  return (
    <div className="flex items-center gap-5 flex-wrap">
      {LEGEND.map(item => (
        <div key={item.label} className="flex items-center gap-2">
          <span className="w-4 h-4 rounded"
                style={{ backgroundColor: '#1e293b', border: `2px solid ${item.color}` }} />
          <span className="text-base text-slate-300">{item.label}</span>
        </div>
      ))}
    </div>
  )
}

function Box({ x, y, w, h, label, sub, accent }) {
  return (
    <g>
      <rect x={x} y={y} width={w} height={h} rx="10"
            fill={C.box} stroke={accent || C.boxLine} strokeWidth="2" />
      <text x={x + w / 2} y={sub ? y + h / 2 - 9 : y + h / 2 + 1}
            textAnchor="middle" dominantBaseline="central"
            fill={C.text} fontSize="18" fontWeight="600">{label}</text>
      {sub && (
        <text x={x + w / 2} y={y + h / 2 + 14}
              textAnchor="middle" dominantBaseline="central"
              fill={C.sub} fontSize="14.5">{sub}</text>
      )}
    </g>
  )
}

const Arrow = ({ d }) => (
  <path d={d} fill="none" stroke={C.arrow} strokeWidth="2.2" markerEnd="url(#pd-arrow)" />
)

const Defs = () => (
  <defs>
    <marker id="pd-arrow" viewBox="0 0 10 10" refX="8" refY="5"
            markerWidth="5" markerHeight="5" orient="auto-start-reverse">
      <path d="M1 1L8 5L1 9" fill="none" stroke={C.arrow}
            strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    </marker>
  </defs>
)

function ChainDiagram() {
  return (
    <svg viewBox="0 0 400 300" className="w-full">
      <Defs />
      <Box x={130} y={8}   w={140} h={46} label="Input" sub="season year" />
      <Arrow d="M200 54 L200 74" />
      <Box x={74}  y={76}  w={252} h={54} label="Step 1 · Scout" sub="analyze the squad" accent={C.llm} />
      <Arrow d="M200 130 L200 150" />
      <Box x={74}  y={152} w={252} h={54} label="Step 2 · Condense" sub="3 key takeaways" accent={C.llm} />
      <Arrow d="M200 206 L200 226" />
      <Box x={74}  y={228} w={252} h={54} label="Step 3 · Translate" sub="target language" accent={C.llm} />
    </svg>
  )
}

/**
 * Routing — with the human-in-the-loop gate on the confidential branch.
 *
 * Order matters and is easy to get wrong: the gate sits AFTER the rumors
 * specialist, not before it. The specialist is the one that decides the
 * secret tool is needed; only then does getSecretRumors() block on human
 * approval. Asking a human first would mean asking before anyone knows
 * whether the data is needed at all.
 *
 * The two arrows between them show the round trip:
 *   specialist --calls--> approval gate --data or ACCESS DENIED--> specialist
 */
function RoutingDiagram() {
  return (
    <svg viewBox="0 0 400 390" className="w-full">
      <Defs />
      <Box x={4}   y={126} w={100} h={48} label="Input" sub="question" />
      <Arrow d="M104 150 L128 150" />
      <Box x={130} y={120} w={136} h={60} label="Router" sub="cheap classifier" accent={C.control} />
      <Arrow d="M266 136 L282 136 L282 46 L298 46" />
      <Arrow d="M266 150 L298 150" />
      <Arrow d="M266 164 L282 164 L282 262 L298 262" />
      <Box x={292} y={20}  w={106} h={52} label="Squad" sub="specialist" accent={C.llm} />
      <Box x={292} y={124} w={106} h={52} label="Transfers" sub="specialist" accent={C.llm} />
      <Box x={292} y={236} w={106} h={52} label="Rumors" sub="specialist" accent={C.llm} />

      {/* round trip: the specialist calls the gated tool and waits */}
      <Arrow d="M318 288 L318 322" />
      <Arrow d="M372 322 L372 288" />
      <Box x={292} y={324} w={106} h={52} label="Approval 🔒" sub="human decides" accent={C.control} />

      <text x={140} y={300} textAnchor="middle" fill={C.sub} fontSize="14.5">
        specialist calls
      </text>
      <text x={140} y={320} textAnchor="middle" fill={C.sub} fontSize="14.5">
        getSecretRumors() and
      </text>
      <text x={140} y={340} textAnchor="middle" fill={C.sub} fontSize="14.5">
        blocks; data or
      </text>
      <text x={140} y={360} textAnchor="middle" fill={C.sub} fontSize="14.5">
        ACCESS DENIED returns
      </text>
    </svg>
  )
}

function ParallelDiagram() {
  return (
    <svg viewBox="0 0 400 300" className="w-full">
      <Defs />
      <Box x={4}   y={126} w={104} h={48} label="Fan-out" sub="candidates" />
      <Arrow d="M108 140 L124 140 L124 46 L142 46" />
      <Arrow d="M108 150 L142 150" />
      <Arrow d="M108 160 L124 160 L124 254 L142 254" />
      <Box x={144} y={20}  w={136} h={52} label="LLM · score A" accent={C.llm} />
      <Box x={144} y={124} w={136} h={52} label="LLM · score B" accent={C.llm} />
      <Box x={144} y={228} w={136} h={52} label="LLM · score C" accent={C.llm} />
      <Arrow d="M280 46 L298 46 L298 140 L314 140" />
      <Arrow d="M280 150 L314 150" />
      <Arrow d="M280 254 L298 254 L298 160 L314 160" />
      <Box x={306} y={122} w={92} h={56} label="Aggregate" sub="code or LLM" accent={C.control} />
    </svg>
  )
}

/**
 * Evaluator-optimizer — propose ONCE, then score/fix in a loop.
 *
 * THE DIAGRAM USED TO LIE ABOUT THE LOOP. It showed the feedback arrow
 * returning to the Generator, i.e. "regenerate the whole answer with the
 * critique attached". Both modules now do the other thing: the generator
 * runs once, outside the loop, and a separate FIXER improves the existing
 * proposal. Spring AI was rewritten to match LangChain4j on 2026-09-02 —
 * before that the two modules ran different algorithms under one pattern
 * name and the side-by-side timings compared different work.
 *
 * It matters for reading the waterfall in Tempo: the trace hint promises
 * "one chat, then N repetitions of a score+fix pair", and a diagram with
 * the arrow on the generator sets up the wrong expectation.
 *
 * The accept path is an explicit labelled arrow rather than a floating
 * "OK" box — previously the reader had to infer what that box was attached
 * to.
 *
 * WHY THIS ONE IS 410 WIDE AND THE OTHERS ARE 400. The accept label carries
 * the actual threshold, and "score ≥ 0.85" is wider than the OK box it sits
 * under. At 400 the last character was clipped by the viewBox edge, which
 * read as "0.8" with a stray mark — i.e. the diagram appeared to contradict
 * the description above it. The whole group moved left and the canvas gained
 * 10 units; the 2.5% scale difference against the other diagrams is
 * invisible, a truncated threshold is not.
 *
 * The number is duplicated from THRESHOLD in BOTH pattern classes. Three
 * places, one value — change them together or not at all.
 */
function EvaluatorDiagram() {
  return (
    <svg viewBox="0 0 410 320" className="w-full">
      <Defs />

      {/* propose once — outside the loop */}
      <Box x={4}   y={20}  w={96}  h={48} label="Task" sub="season" />
      <Arrow d="M100 44 L124 44" />
      <Box x={126} y={14}  w={150} h={60} label="Generator" sub="proposes squad · once" accent={C.llm} />
      <Arrow d="M201 74 L201 108" />

      {/* the loop: evaluate, then fix, then evaluate again */}
      <Box x={126} y={110} w={150} h={60} label="Evaluator" sub="score + feedback" accent={C.control} />
      <Arrow d="M201 170 L201 204" />
      <Box x={126} y={206} w={150} h={60} label="Fixer" sub="improves the lineup" accent={C.llm} />

      {/* loop back to the evaluator — NOT to the generator */}
      <Arrow d="M126 236 L60 236 L60 140 L124 140" />
      <text x={40} y={286} textAnchor="start" fill={C.sub} fontSize="14.5">
        score &lt; threshold → fix, then score again
      </text>
      <text x={40} y={306} textAnchor="start" fill={C.sub} fontSize="14.5">
        (max N iterations)
      </text>

      {/* accept path — shifted left so the threshold label fits the canvas */}
      <Arrow d="M276 140 L310 140 L310 92 L328 92" />
      <Box x={330} y={68}  w={62} h={46} label="OK" />
      <text x={361} y={132} textAnchor="middle" fill={C.sub} fontSize="14.5">
        score ≥ 0.85
      </text>
    </svg>
  )
}

function OrchestratorDiagram() {
  return (
    <svg viewBox="0 0 400 300" className="w-full">
      <Defs />
      <Box x={104} y={12}  w={192} h={60} label="Orchestrator" sub="plans + synthesizes" accent={C.control} />
      <Arrow d="M150 72 L150 104 L64 104 L64 138" />
      <Arrow d="M200 72 L200 138" />
      <Arrow d="M250 72 L250 104 L336 104 L336 138" />
      <Box x={4}   y={140} w={120} h={60} label="Worker" sub="getSquad" accent={C.llm} />
      <Box x={140} y={140} w={120} h={60} label="Worker" sub="getPlayerStats" accent={C.llm} />
      <Box x={276} y={140} w={120} h={60} label="Worker" sub="?" accent={C.llm} />
      <Arrow d="M64 200 L64 236 L150 236 L150 216" />
      <Arrow d="M200 200 L200 216" />
      <Arrow d="M336 200 L336 236 L250 236 L250 216" />
      {/* The third worker is deliberately unnamed: the whole point of this
          pattern is that the orchestrator decides WHICH tools to call, and
          how many, at runtime. Three fixed named boxes suggested a known
          plan — which is parallelization, not orchestrator-workers. */}
      <text x={200} y={266} textAnchor="middle" fill={C.sub} fontSize="14.5">
        which workers, and how many,
      </text>
      <text x={200} y={286} textAnchor="middle" fill={C.sub} fontSize="14.5">
        is decided by the model at runtime
      </text>
    </svg>
  )
}

const DIAGRAMS = {
  chain:        ChainDiagram,
  routing:      RoutingDiagram,
  parallel:     ParallelDiagram,
  evaluator:    EvaluatorDiagram,
  orchestrator: OrchestratorDiagram,
}

export default function PatternDiagram({ patternId }) {
  const Diagram = DIAGRAMS[patternId]
  return Diagram ? <Diagram /> : null
}
