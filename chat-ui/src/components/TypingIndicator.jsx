export default function TypingIndicator({ agentName, agentColor }) {
  return (
    <div className="flex justify-start mb-4">
      <div
        className="w-7 h-7 rounded-full flex items-center justify-center text-[10px] font-bold mr-2 shrink-0"
        style={{ backgroundColor: agentColor + '33', color: agentColor }}
      >
        AI
      </div>
      <div className="chat-bubble-agent flex items-center gap-1.5 py-4 px-5">
        <span className="typing-dot" style={{ animationDelay: '0ms' }} />
        <span className="typing-dot" style={{ animationDelay: '150ms' }} />
        <span className="typing-dot" style={{ animationDelay: '300ms' }} />
        <span className="text-xs text-slate-500 ml-2">{agentName} pisze...</span>
      </div>
    </div>
  )
}
