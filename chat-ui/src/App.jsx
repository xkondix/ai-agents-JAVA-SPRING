import { useState } from 'react'
import { RefreshCw, Bot } from 'lucide-react'
import AgentSelector from './components/AgentSelector.jsx'
import ChatWindow from './components/ChatWindow.jsx'
import { useAgentHealth } from './hooks/useAgentHealth.js'

export default function App() {
  const [selectedAgent, setSelectedAgent] = useState(null)
  const { status, refresh } = useAgentHealth()

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
              {upCount} / {Object.keys(status).length} agentow online
            </p>
            <button
              onClick={refresh}
              className="p-1 rounded text-slate-600 hover:text-slate-400 transition-colors"
              title="Odswiez status"
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

        {/* footer */}
        <div className="px-4 py-3 border-t border-slate-800">
          <p className="text-[10px] text-slate-700 text-center">
            Konrad Kowalczyk · xkondix · 2025
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
              Wybierz agenta
            </h2>
            <p className="text-sm text-slate-600 max-w-sm">
              Uruchom przynajmniej jednego agenta z projektu,
              a pojawi sie tutaj automatycznie.
            </p>
            <div className="mt-6 text-xs text-slate-700 space-y-1">
              <p>langchain4j-agent → port 8082</p>
              <p>langchain4j-mcp   → port 8083</p>
              <p>spring-ai-agent   → port 8084</p>
              <p>spring-ai-mcp     → port 8085</p>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
