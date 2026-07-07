import { useState } from 'react'
import { RefreshCw, Bot, ShieldCheck } from 'lucide-react'
import { Link } from 'react-router-dom'
import AgentSelector from './components/AgentSelector.jsx'
import ChatWindow from './components/ChatWindow.jsx'
import { useAgentHealth } from './hooks/useAgentHealth.js'
import { usePendingApprovals } from './hooks/usePendingApprovals.js'

export default function App() {
  const [selectedAgent, setSelectedAgent] = useState(null)
  const { status, refresh } = useAgentHealth()
  const { count: pendingCount } = usePendingApprovals()

  const upCount = Object.values(status).filter(s => s === 'up').length

  return (
    <div className="flex h-screen bg-slate-950">

      {/* sidebar */}
      <div className="w-72 shrink-0 border-r border-slate-800 flex flex-col">

        {/* logo */}
        <div className="px-4 py-4 border-b border-slate-800">
          <div className="flex items-center gap-2.5 mb-1">
            <div className="w-8 h-8 rounded-lg bg-indigo-600 flex items-center justify-center">
              <Bot size={16} className="text-white" />
            </div>
            <div>
              <p className="font-bold text-slate-100 text-sm">AI Agents Chat</p>
              <p className="text-[10px] text-slate-500">ai-agents-JAVA-SPRING</p>
            </div>
          </div>
          <div className="flex items-center justify-between mt-2">
            <p className="text-xs text-slate-600">
              {upCount} / {Object.keys(status).length} agents online
            </p>
            <button
              onClick={refresh}
              className="p-1 rounded text-slate-600 hover:text-slate-400 transition-colors"
              title="Refresh status"
            >
              <RefreshCw size={12} />
            </button>
          </div>
        </div>

        {/* agent list */}
        <div className="flex-1 overflow-y-auto">
          <AgentSelector
            selected={selectedAgent}
            onSelect={setSelectedAgent}
            status={status}
          />
        </div>

        {/* approvals link */}
        <div className="px-4 py-3 border-t border-slate-800">
          <Link
            to="/approvals"
            className="flex items-center justify-between w-full px-3 py-2 rounded-xl
                       bg-slate-800 hover:bg-slate-700 transition-colors"
          >
            <div className="flex items-center gap-2">
              <ShieldCheck size={14} className="text-amber-400" />
              <span className="text-xs font-medium text-slate-300">Approval Flow</span>
            </div>
            {pendingCount > 0 && (
              <span className="text-[10px] font-bold bg-amber-500 text-white
                               px-1.5 py-0.5 rounded-full animate-pulse">
                {pendingCount}
              </span>
            )}
          </Link>
        </div>

        {/* footer */}
        <div className="px-4 py-2 border-t border-slate-800">
          <p className="text-[10px] text-slate-700 text-center">
            Konrad Kowalczyk · xkondix · 2026
          </p>
        </div>
      </div>

      {/* main area */}
      <div className="flex-1 flex flex-col min-w-0">
        {selectedAgent ? (
          <ChatWindow key={selectedAgent.id} agent={selectedAgent} />
        ) : (
          <div className="flex flex-col items-center justify-center h-full text-center px-8">
            <div className="w-16 h-16 rounded-2xl bg-slate-800 flex items-center justify-center mb-5">
              <Bot size={28} className="text-slate-500" />
            </div>
            <h2 className="text-lg font-semibold text-slate-300 mb-2">
              Select an agent
            </h2>
            <p className="text-sm text-slate-600 max-w-sm">
              Start at least one agent from the project
              and it will appear here automatically.
            </p>
            <div className="mt-6 text-xs text-slate-700 space-y-1">
              <p>raw-agent               → port 8090</p>
              <p>langchain4j-agent-local → port 8082</p>
              <p>langchain4j-agent-mcp   → port 8083</p>
              <p>spring-ai-agent-local   → port 8084</p>
              <p>spring-ai-agent-mcp     → port 8085</p>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
