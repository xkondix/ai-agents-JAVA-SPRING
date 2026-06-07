import ReactMarkdown from 'react-markdown'
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter'
import { vscDarkPlus } from 'react-syntax-highlighter/dist/esm/styles/prism'
import { Copy, Check } from 'lucide-react'
import { useState } from 'react'

function CopyButton({ text }) {
  const [copied, setCopied] = useState(false)
  const copy = () => {
    navigator.clipboard.writeText(text)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }
  return (
    <button onClick={copy} className="p-1 rounded hover:bg-slate-600 transition-colors">
      {copied
        ? <Check size={13} className="text-green-400" />
        : <Copy size={13} className="text-slate-400" />}
    </button>
  )
}

export default function Message({ msg, agentColor }) {
  const isUser = msg.role === 'user'

  return (
    <div className={`flex ${isUser ? 'justify-end' : 'justify-start'} mb-4`}>
      {/* avatar agenta */}
      {!isUser && (
        <div
          className="w-7 h-7 rounded-full flex items-center justify-center text-[10px] font-bold mr-2 shrink-0 mt-1"
          style={{ backgroundColor: agentColor + '33', color: agentColor }}
        >
          AI
        </div>
      )}

      <div className={isUser ? 'chat-bubble-user' : 'chat-bubble-agent'}>
        {isUser ? (
          <p className="text-sm whitespace-pre-wrap">{msg.content}</p>
        ) : (
          <ReactMarkdown
            className="text-sm prose prose-invert prose-sm max-w-none"
            components={{
              code({ node, inline, className, children, ...props }) {
                const match = /language-(\w+)/.exec(className || '')
                const code = String(children).replace(/\n$/, '')
                if (!inline && match) {
                  return (
                    <div className="relative group">
                      <div className="absolute right-2 top-2 opacity-0 group-hover:opacity-100 transition-opacity">
                        <CopyButton text={code} />
                      </div>
                      <SyntaxHighlighter
                        style={vscDarkPlus}
                        language={match[1]}
                        PreTag="div"
                        className="!rounded-lg !text-xs"
                        {...props}
                      >
                        {code}
                      </SyntaxHighlighter>
                    </div>
                  )
                }
                return (
                  <code className="bg-slate-700 px-1.5 py-0.5 rounded text-xs" {...props}>
                    {children}
                  </code>
                )
              }
            }}
          >
            {msg.content}
          </ReactMarkdown>
        )}

        {/* timestamp */}
        <p className={`text-[10px] mt-1.5 ${isUser ? 'text-indigo-200' : 'text-slate-500'} text-right`}>
          {new Date(msg.timestamp).toLocaleTimeString('pl-PL', {
            hour: '2-digit', minute: '2-digit', second: '2-digit'
          })}
        </p>
      </div>

      {/* avatar usera */}
      {isUser && (
        <div className="w-7 h-7 rounded-full bg-indigo-600 flex items-center justify-center text-[10px] font-bold ml-2 shrink-0 mt-1">
          TY
        </div>
      )}
    </div>
  )
}
