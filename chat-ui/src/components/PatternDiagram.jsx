/**
 * Inline SVG schematics for the Patterns Lab — one diagram per pattern,
 * English labels, styled to match the dark slate theme.
 *
 * Pure presentational component: <PatternDiagram patternId="chain" />
 */

const C = {
  box:     '#1e293b', // slate-800
  boxLine: '#475569', // slate-600
  text:    '#e2e8f0', // slate-200
  sub:     '#94a3b8', // slate-400
  indigo:  '#818cf8',
  emerald: '#34d399',
  amber:   '#fbbf24',
  arrow:   '#64748b', // slate-500
}

function Box({ x, y, w, h, label, sub, accent }) {
  return (
    <g>
      <rect x={x} y={y} width={w} height={h} rx="8"
            fill={C.box} stroke={accent || C.boxLine} strokeWidth="1.2" />
      <text x={x + w / 2} y={sub ? y + h / 2 - 6 : y + h / 2 + 1}
            textAnchor="middle" dominantBaseline="central"
            fill={C.text} fontSize="12" fontWeight="600">{label}</text>
      {sub && (
        <text x={x + w / 2} y={y + h / 2 + 11}
              textAnchor="middle" dominantBaseline="central"
              fill={C.sub} fontSize="9.5">{sub}</text>
      )}
    </g>
  )
}

function Arrow({ d }) {
  return <path d={d} fill="none" stroke={C.arrow} strokeWidth="1.6"
               markerEnd="url(#pd-arrow)" />
}

function Defs() {
  return (
    <defs>
      <marker id="pd-arrow" viewBox="0 0 10 10" refX="8" refY="5"
              markerWidth="6" markerHeight="6" orient="auto-start-reverse">
        <path d="M1 1L8 5L1 9" fill="none" stroke={C.arrow}
              strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
      </marker>
    </defs>
  )
}

function ChainDiagram() {
  return (
    <svg viewBox="0 0 360 210" className="w-full">
      <Defs />
      <Box x={130} y={10}  w={100} h={34} label="Input" sub="season year" />
      <Arrow d="M180 44 L180 60" />
      <Box x={100} y={62}  w={160} h={38} label="Step 1 · Scout" sub="analyze the squad" accent={C.indigo} />
      <Arrow d="M180 100 L180 116" />
      <Box x={100} y={118} w={160} h={38} label="Step 2 · Condense" sub="3 key takeaways" accent={C.indigo} />
      <Arrow d="M180 156 L180 172" />
      <Box x={100} y={174} w={160} h={34} label="Step 3 · Translate" sub="target language" accent={C.emerald} />
    </svg>
  )
}

function RoutingDiagram() {
  return (
    <svg viewBox="0 0 360 210" className="w-full">
      <Defs />
      <Box x={10}  y={88} w={78}  h={36} label="Input" sub="question" />
      <Arrow d="M88 106 L118 106" />
      <Box x={120} y={82} w={104} h={48} label="Router" sub="cheap classifier" accent={C.amber} />
      <Arrow d="M224 94 L250 94 L250 32 L268 32" />
      <Arrow d="M224 106 L268 106" />
      <Arrow d="M224 118 L250 118 L250 180 L268 180" />
      <Box x={270} y={14}  w={84} h={36} label="Squad" sub="specialist" accent={C.indigo} />
      <Box x={270} y={88}  w={84} h={36} label="Transfers" sub="specialist" accent={C.indigo} />
      <Box x={270} y={162} w={84} h={36} label="Rumors 🔒" sub="specialist" accent={C.indigo} />
    </svg>
  )
}

function ParallelDiagram() {
  return (
    <svg viewBox="0 0 360 210" className="w-full">
      <Defs />
      <Box x={10} y={88} w={78} h={36} label="Fan-out" sub="candidates" />
      <Arrow d="M88 98 L112 98 L112 32 L130 32" />
      <Arrow d="M88 106 L130 106" />
      <Arrow d="M88 114 L112 114 L112 180 L130 180" />
      <Box x={132} y={14}  w={110} h={36} label="LLM · score A" accent={C.indigo} />
      <Box x={132} y={88}  w={110} h={36} label="LLM · score B" accent={C.indigo} />
      <Box x={132} y={162} w={110} h={36} label="LLM · score C" accent={C.indigo} />
      <Arrow d="M242 32 L262 32 L262 98 L276 98" />
      <Arrow d="M242 106 L276 106" />
      <Arrow d="M242 180 L262 180 L262 114 L276 114" />
      <Box x={278} y={86} w={76} h={40} label="Aggregate" sub="code or LLM" accent={C.emerald} />
    </svg>
  )
}

function EvaluatorDiagram() {
  return (
    <svg viewBox="0 0 360 210" className="w-full">
      <Defs />
      <Box x={10} y={60} w={72} h={36} label="Task" sub="season" />
      <Arrow d="M82 78 L104 78" />
      <Box x={106} y={54} w={104} h={48} label="Generator" sub="proposes squad" accent={C.indigo} />
      <Arrow d="M210 78 L238 78" />
      <Box x={240} y={54} w={104} h={48} label="Evaluator" sub="score + feedback" accent={C.amber} />
      <Arrow d="M292 102 L292 140 L158 140 L158 102" />
      <text x={225} y={156} textAnchor="middle" fill={C.sub} fontSize="10">
        score &lt; 0.8 → feedback loops back (max N)
      </text>
      <Arrow d="M292 54 L292 30 L318 30" />
      <Box x={320} y={14} w={34} h={32} label="OK" accent={C.emerald} />
    </svg>
  )
}

function OrchestratorDiagram() {
  return (
    <svg viewBox="0 0 360 210" className="w-full">
      <Defs />
      <Box x={110} y={12} w={140} h={48} label="Orchestrator" sub="plans + synthesizes" accent={C.amber} />
      <Arrow d="M140 60 L140 82 L62 82 L62 104" />
      <Arrow d="M180 60 L180 104" />
      <Arrow d="M220 60 L220 82 L298 82 L298 104" />
      <Box x={16}  y={106} w={92} h={44} label="Worker" sub="getSquad" accent={C.indigo} />
      <Box x={134} y={106} w={92} h={44} label="Worker" sub="getPlayerStats" accent={C.indigo} />
      <Box x={252} y={106} w={92} h={44} label="Worker" sub="getTransfers" accent={C.indigo} />
      <Arrow d="M62 150 L62 172 L140 172 L140 162" />
      <Arrow d="M180 150 L180 162" />
      <Arrow d="M298 150 L298 172 L220 172 L220 162" />
      <text x={180} y={196} textAnchor="middle" fill={C.sub} fontSize="10">
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
