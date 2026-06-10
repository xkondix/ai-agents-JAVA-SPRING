import { AGENTS } from '../agents.js'

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
            <button
              key={agent.id}
              disabled={!isUp}
              onClick={() => onSelect(agent)}
              className={[
                'flex items-center gap-3 px-3 py-2.5 rounded-xl text-left transition-all',
                'border',
                active
                  ? `border-[${agent.color}] bg-slate-800`
                  : 'border-slate-700 hover:border-slate-600 hover:bg-slate-800/50',
                !isUp && 'opacity-40 cursor-not-allowed',
              ].join(' ')}
              style={active ? { borderColor: agent.color } : {}}
            >
              {/* icon */}
              <span
                className="text-[10px] font-bold px-1.5 py-0.5 rounded"
                style={{ backgroundColor: agent.color + '33', color: agent.color }}
              >
                {agent.icon}
              </span>

              {/* info */}
              <div className="flex-1 min-w-0">
                <p className="text-sm font-medium text-slate-200 truncate">
                  {agent.name}
                </p>
                <p className="text-xs text-slate-500 truncate">
                  {agent.description}
                </p>
              </div>

              {/* status dot */}
              <span className="flex items-center gap-1.5 shrink-0">
                <span className={`w-2 h-2 rounded-full ${STATUS_DOT[s]}`} />
                <span className="text-xs text-slate-500">{STATUS_LABEL[s]}</span>
              </span>
            </button>
          )
        })}
      </div>
    </div>
  )
}
