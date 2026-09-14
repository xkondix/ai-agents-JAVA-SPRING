/**
 * Flow diagram for Multimodal Lab, in the same visual language as
 * PatternDiagram.jsx — slate boxes for data, sky for model calls, amber for
 * control.
 *
 * VERTICAL ON PURPOSE. The first version was the wide layout used in Patterns
 * Lab: three tool boxes side by side, 640 units across. That page gives a
 * diagram ~460px; this one lives in a 440px side panel, so the browser scaled
 * a 640-unit canvas down by ~40% and 17px labels rendered at 10px. Narrow
 * column, narrow viewBox — the boxes stack instead, and the text keeps its
 * size.
 *
 * The dashed container is doing real work. Drawing an arrow from each of the
 * three tools back to the router and down to storage means six lines crossing
 * a 400-unit space; grouping them lets the picture say the true thing once —
 * results go back to the router, artifacts go to disk — with three lines
 * instead of six.
 *
 * That return arrow is the whole point of the diagram: it is what makes
 * chaining possible. The router asks for a tool, reads the result, and asks
 * for another. Nobody wrote that loop — ToolCallingAdvisor runs it.
 */

const C = {
  box: '#1e293b',
  boxLine: '#64748b',
  text: '#f1f5f9',
  sub: '#94a3b8',
  llm: '#38bdf8',
  control: '#fbbf24',
  arrow: '#94a3b8',
}

function Box({ x, y, w, h, label, sub, accent }) {
  return (
    <g>
      <rect x={x} y={y} width={w} height={h} rx="9"
            fill={C.box} stroke={accent || C.boxLine} strokeWidth="1.5" />
      <text x={x + w / 2} y={sub ? y + h / 2 - 8 : y + h / 2 + 1}
            textAnchor="middle" dominantBaseline="central"
            fill={C.text} fontSize="15" fontWeight="600">{label}</text>
      {sub && (
        <text x={x + w / 2} y={y + h / 2 + 11}
              textAnchor="middle" dominantBaseline="central"
              fill={C.sub} fontSize="12">{sub}</text>
      )}
    </g>
  )
}

const Arrow = ({ d }) => (
  <path d={d} fill="none" stroke={C.arrow} strokeWidth="1.8" markerEnd="url(#mm-arrow)" />
)

export default function MultimodalDiagram() {
  return (
    <svg viewBox="0 0 400 434" className="w-full">
      <defs>
        <marker id="mm-arrow" viewBox="0 0 10 10" refX="8" refY="5"
                markerWidth="5" markerHeight="5" orient="auto-start-reverse">
          <path d="M1 1L8 5L1 9" fill="none" stroke={C.arrow}
                strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
        </marker>
      </defs>

      <Box x={70} y={8} w={240} h={46} label="Text · photo · voice" sub="voice transcribed first" />
      <Arrow d="M190 54 L190 74" />

      <Box x={70} y={76} w={240} h={50} label="Router" sub="cheap model, sees no pixels" />
      <rect x={70} y={76} width={240} height={50} rx="9"
            fill="none" stroke={C.control} strokeWidth="1.5" />

      <Arrow d="M190 126 L190 148" />

      {/* the three tools, grouped so the return path is one line, not three */}
      <rect x={56} y={150} width={268} height={182} rx="12"
            fill="none" stroke={C.boxLine} strokeWidth="1" strokeDasharray="4 4" />
      <text x={66} y={166} fill={C.sub} fontSize="11">tools · one model each</text>

      <Box x={76} y={174} w={228} h={44} label="analyze_image" sub="vision model" accent={C.llm} />
      <Box x={76} y={226} w={228} h={44} label="generate_image" sub="image model" accent={C.llm} />
      <Box x={76} y={278} w={228} h={44} label="speak_text" sub="tts model" accent={C.llm} />

      {/* results return to the router — this is what makes chaining work */}
      <Arrow d="M324 240 L362 240 L362 101 L314 101" />
      <text x={370} y={175} fill={C.sub} fontSize="11"
            transform="rotate(-90 370 175)" textAnchor="middle">results</text>

      <Arrow d="M190 332 L190 356" />
      <Box x={70} y={358} w={240} h={46} label="Media store" sub="files on disk, URLs back" />
    </svg>
  )
}
