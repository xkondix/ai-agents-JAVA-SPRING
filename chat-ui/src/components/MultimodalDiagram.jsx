/**
 * Flow diagram for Multimodal Lab, in the same visual language as
 * PatternDiagram.jsx — slate boxes for data, sky for model calls, amber for
 * control.
 *
 * VERTICAL ON PURPOSE. The first version used the wide layout from Patterns
 * Lab; this one lives in a 400px side panel, so a 640-unit canvas scaled down
 * by 40% and 17px labels rendered at 10px. Narrow column, narrow viewBox.
 *
 * The dashed container is doing real work. Drawing an arrow from each of the
 * five tools back to the router and down to storage means ten lines crossing
 * a 400-unit space; grouping them says the true thing once — results go back
 * to the router, artifacts go to disk — with three lines instead of ten.
 *
 * That return arrow is the whole point: it is what makes chaining possible.
 * The router asks for a tool, reads the result, asks for another. Nobody
 * wrote that loop — ToolCallingAdvisor runs it.
 *
 * EACH TOOL CARRIES ITS PROTOCOL, not just its model. That is the thing this
 * module actually demonstrates: one provider, one key, one base URL, and four
 * genuinely different shapes of API behind five tools. Spring AI reaches
 * three of them; music and video are hand-written HTTP.
 *
 * Video is drawn dimmed because it is off by default (multimodal.video.enabled) —
 * $1.60 for four seconds.
 */

const C = {
  box: '#1e293b',
  boxLine: '#64748b',
  text: '#f1f5f9',
  sub: '#94a3b8',
  llm: '#38bdf8',
  control: '#fbbf24',
  arrow: '#94a3b8',
  muted: '#475569',
}

function Box({ x, y, w, h, label, sub, accent, dim }) {
  return (
    <g opacity={dim ? 0.55 : 1}>
      <rect x={x} y={y} width={w} height={h} rx="9"
            fill={C.box} stroke={accent || C.boxLine} strokeWidth="1.5"
            strokeDasharray={dim ? '4 3' : undefined} />
      <text x={x + w / 2} y={sub ? y + h / 2 - 8 : y + h / 2 + 1}
            textAnchor="middle" dominantBaseline="central"
            fill={C.text} fontSize="14" fontWeight="600">{label}</text>
      {sub && (
        <text x={x + w / 2} y={y + h / 2 + 10}
              textAnchor="middle" dominantBaseline="central"
              fill={C.sub} fontSize="11">{sub}</text>
      )}
    </g>
  )
}

const Arrow = ({ d }) => (
  <path d={d} fill="none" stroke={C.arrow} strokeWidth="1.8" markerEnd="url(#mm-arrow)" />
)

export default function MultimodalDiagram() {
  return (
    <svg viewBox="0 0 400 540" className="w-full">
      <defs>
        <marker id="mm-arrow" viewBox="0 0 10 10" refX="8" refY="5"
                markerWidth="5" markerHeight="5" orient="auto-start-reverse">
          <path d="M1 1L8 5L1 9" fill="none" stroke={C.arrow}
                strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
        </marker>
      </defs>

      <Box x={70} y={8} w={240} h={44} label="Text · photo · voice" sub="voice transcribed first" />
      <Arrow d="M190 52 L190 72" />

      <Box x={70} y={74} w={240} h={48} label="Router" sub="cheap model, sees no pixels" />
      <rect x={70} y={74} width={240} height={48} rx="9"
            fill="none" stroke={C.control} strokeWidth="1.5" />

      <Arrow d="M190 122 L190 144" />

      {/* five tools, grouped so the return path is one line, not five */}
      <rect x={56} y={146} width={268} height={276} rx="12"
            fill="none" stroke={C.boxLine} strokeWidth="1" strokeDasharray="4 4" />
      <text x={66} y={162} fill={C.sub} fontSize="10.5">
        tools · own model AND own protocol
      </text>

      <Box x={76} y={170} w={228} h={42} label="analyze_image" sub="vision · chat API" accent={C.llm} />
      <Box x={76} y={218} w={228} h={42} label="generate_image" sub="image · images API" accent={C.llm} />
      <Box x={76} y={266} w={228} h={42} label="speak_text" sub="tts · audio API" accent={C.llm} />
      <Box x={76} y={314} w={228} h={42} label="generate_music" sub="Lyria · SSE stream" accent={C.llm} />
      <Box x={76} y={362} w={228} h={42} label="generate_video" sub="Veo · job queue · off" accent={C.llm} dim />

      {/* results return to the router — this is what makes chaining work */}
      <Arrow d="M324 285 L362 285 L362 99 L314 99" />
      <text x={372} y={195} fill={C.sub} fontSize="10.5"
            transform="rotate(-90 372 195)" textAnchor="middle">results</text>

      <Arrow d="M190 422 L190 444" />
      <Box x={70} y={446} w={240} h={44} label="Media store" sub="files on disk, URLs back" />

      <text x={190} y={512} textAnchor="middle" fill={C.sub} fontSize="10.5">
        one provider · one key · four protocols
      </text>
      <text x={190} y={528} textAnchor="middle" fill={C.muted} fontSize="10.5">
        Spring AI reaches three of them
      </text>
    </svg>
  )
}
