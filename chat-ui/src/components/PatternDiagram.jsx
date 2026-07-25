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

function EvaluatorDiagram() {
  return (
    <svg viewBox="0 0 400 300" className="w-full">
      <Defs />
      <Box x={4}   y={94}  w={96}  h={48} label="Task" sub="season" />
      <Arrow d="M100 118 L122 118" />
      <Box x={124} y={88}  w={136} h={60} label="Generator" sub="proposes squad" accent={C.llm} />
      <Arrow d="M260 118 L280 118" />
      <Box x={282} y={88}  w={116} h={60} label="Evaluator" sub="score + feedback" accent={C.control} />
      <Arrow d="M340 148 L340 198 L192 198 L192 148" />
      <text x={266} y={228} textAnchor="middle" fill={C.sub} fontSize="14.5">
        score &lt; 0.8 → loop back
      </text>
      <text x={266} y={250} textAnchor="middle" fill={C.sub} fontSize="14.5">
        (max N iterations)
      </text>
      <Arrow d="M340 88 L340 54 L356 54" />
      <Box x={340} y={22}  w={58} h={46} label="OK" />
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
      <Box x={276} y={140} w={120} h={60} label="Worker" sub="getTransfers" accent={C.llm} />
      <Arrow d="M64 200 L64 236 L150 236 L150 216" />
      <Arrow d="M200 200 L200 216" />
      <Arrow d="M336 200 L336 236 L250 236 L250 216" />
      <text x={200} y={270} textAnchor="middle" fill={C.sub} fontSize="14.5">
        results flow back for synthesis
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
