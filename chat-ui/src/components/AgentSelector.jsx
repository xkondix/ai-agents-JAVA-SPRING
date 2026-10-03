import { AGENTS } from '../agents.js'

/**
 * Sidebar list of agents.
 *
 * ONE TILE PER PROCESS. A tile is a running module, not an endpoint — an
 * earlier version gave langchain4j-agent-local two tiles because it has two
 * chat endpoints, and a stopped module then painted two red tiles and looked
 * like two separate failures.
 *
 * Endpoints inside one process are rendered as `variants`: a row of pills
 * inside the tile. Selecting one hands chatApi that variant's chatPath, so
 * nothing downstream has to know whether an agent has variants at all.
 */

const STATUS_DOT = {
  up:       'bg-green-400 animate-pulse',
  down:     'bg-red-500',
  checking: 'bg-yellow-400 animate-pulse',
}

const STATUS_LABEL = {
  up:       'Online',
  down:     'Offline',
  checking: 'Checking...',
}

export default function AgentSelector({ selected, onSelect, status }) {
  /**
   * Variants carry their own chatPath; everything else comes from the agent.
   * variantId is what the UI compares against — chatPath would work too, but
   * an id survives a path change.
   */
  const choose = (agent, variant) =>
    onSelect(variant
      ? { ...agent, chatPath: variant.chatPath, variantId: variant.id }
      : agent)

  return (
    <div className="p-4 border-b border-slate-800">
      <p className="text-xs text-slate-500 uppercase tracking-widest mb-3">
        Select agent
      </p>
      <div className="flex flex-col gap-2">
        {AGENTS.map(agent => {
          const s      = status[agent.id] ?? 'checking'
          const active = selected?.id === agent.id
          const isUp   = s === 'up'

          return (
            <div key={agent.id} className="flex flex-col">
              <button
                disabled={!isUp}
                onClick={() => choose(agent, agent.variants?.[0])}
                className={[
                  'flex items-center gap-3 px-3 py-2.5 text-left transition-all border',
                  agent.variants ? 'rounded-t-xl border-b-0' : 'rounded-xl',
                  active
                    ? 'bg-slate-800'
                    : 'border-slate-700 hover:border-slate-600 hover:bg-slate-800/50',
                  !isUp && 'opacity-40 cursor-not-allowed',
                ].join(' ')}
                style={active ? { borderColor: agent.color } : {}}
              >
                <span
                  className="text-[10px] font-bold px-1.5 py-0.5 rounded"
                  style={{ backgroundColor: agent.color + '33', color: agent.color }}
                >
                  {agent.icon}
                </span>

                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium text-slate-200 truncate">
                    {agent.name}
                  </p>
                  <p className="text-xs text-slate-500 truncate">
                    {agent.description}
                  </p>
                </div>

                <span className="flex items-center gap-1.5 shrink-0">
                  <span className={`w-2 h-2 rounded-full ${STATUS_DOT[s]}`} />
                  <span className="text-xs text-slate-500">{STATUS_LABEL[s]}</span>
                </span>
              </button>

              {agent.variants && (
                <div
                  className={[
                    'flex gap-1 px-2 py-2 rounded-b-xl border border-t-0',
                    active ? 'bg-slate-800' : 'border-slate-700',
                    !isUp && 'opacity-40',
                  ].join(' ')}
                  style={active ? { borderColor: agent.color } : {}}
                >
                  {agent.variants.map(variant => {
                    const picked = active && selected?.variantId === variant.id
                    return (
                      <button
                        key={variant.id}
                        disabled={!isUp}
                        title={variant.hint}
                        onClick={() => choose(agent, variant)}
                        className={[
                          'flex-1 px-2 py-1 rounded-lg text-[11px] transition-colors border',
                          picked
                            ? 'text-slate-100'
                            : 'text-slate-500 border-transparent hover:text-slate-300',
                          !isUp && 'cursor-not-allowed',
                        ].join(' ')}
                        style={picked
                          ? { borderColor: agent.color, backgroundColor: agent.color + '22' }
                          : {}}
                      >
                        {variant.label}
                      </button>
                    )
                  })}
                </div>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
