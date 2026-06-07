import { useState, useRef, useEffect } from 'react'
import { Send, Trash2, RefreshCw } from 'lucide-react'
import Message from './Message.jsx'
import TypingIndicator from './TypingIndicator.jsx'
import FileUpload from './FileUpload.jsx'
import { sendMessage, sendFile } from '../api/chatApi.js'

export default function ChatWindow({ agent }) {
  const [messages, setMessages]   = useState([])
  const [input, setInput]         = useState('')
  const [loading, setLoading]     = useState(false)
  const [file, setFile]           = useState(null)
  const [userId]                  = useState(() => 'user-' + Math.random().toString(36).slice(2, 8))
  const bottomRef                 = useRef(null)
  const textareaRef               = useRef(null)

  // scroll to bottom on new message
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, loading])

  // reset when agent changes
  useEffect(() => {
    setMessages([])
    setInput('')
    setFile(null)
  }, [agent.id])

  const addMessage = (role, content) => {
    setMessages(prev => [...prev, { role, content, timestamp: Date.now() }])
  }

  const handleSend = async () => {
    const text = input.trim()
    if (!text && !file) return
    if (loading) return

    const userText = file ? `[${file.name}] ${text}` : text
    addMessage('user', userText)
    setInput('')
    setLoading(true)

    try {
      let response
      if (file) {
        response = await sendFile(agent, file, text, userId)
        setFile(null)
      } else {
        response = await sendMessage(agent, text, userId, chunk => {
          // streaming: update last assistant message
          setMessages(prev => {
            const last = prev[prev.length - 1]
            if (last?.role === 'assistant') {
              return [...prev.slice(0, -1), { ...last, content: chunk }]
            }
            return [...prev, { role: 'assistant', content: chunk, timestamp: Date.now() }]
          })
        })
      }
      // if no streaming — add response
      setMessages(prev => {
        const last = prev[prev.length - 1]
        if (last?.role === 'assistant') return prev // already added by streaming
        return [...prev, { role: 'assistant', content: response, timestamp: Date.now() }]
      })
    } catch (err) {
      addMessage('assistant', `Error: ${err.message}`)
    } finally {
      setLoading(false)
    }
  }

  const handleKey = e => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  const clearChat = () => {
    setMessages([])
    setFile(null)
  }

  return (
    <div className="flex flex-col h-full">

      {/* header */}
      <div
        className="flex items-center justify-between px-5 py-3 border-b border-slate-800"
        style={{ borderBottomColor: agent.color + '44' }}
      >
        <div className="flex items-center gap-3">
          <span
            className="text-xs font-bold px-2 py-1 rounded"
            style={{ backgroundColor: agent.color + '22', color: agent.color }}
          >
            {agent.icon}
          </span>
          <div>
            <p className="font-semibold text-slate-100">{agent.name}</p>
            <p className="text-xs text-slate-500">{agent.description}</p>
          </div>
        </div>
        <button
          onClick={clearChat}
          className="p-2 rounded-lg text-slate-500 hover:text-red-400 hover:bg-slate-800 transition-colors"
          title="Clear chat"
        >
          <Trash2 size={16} />
        </button>
      </div>

      {/* messages */}
      <div className="flex-1 overflow-y-auto px-5 py-4">
        {messages.length === 0 && (
          <div className="flex flex-col items-center justify-center h-full text-center">
            <div
              className="w-14 h-14 rounded-2xl flex items-center justify-center text-xl font-bold mb-4"
              style={{ backgroundColor: agent.color + '22', color: agent.color }}
            >
              {agent.icon}
            </div>
            <p className="text-slate-400 text-sm font-medium">{agent.name}</p>
            <p className="text-slate-600 text-xs mt-1">{agent.description}</p>
            <p className="text-slate-600 text-xs mt-4">Type a message to start</p>
          </div>
        )}
        {messages.map((msg, i) => (
          <Message key={i} msg={msg} agentColor={agent.color} />
        ))}
        {loading && (
          <TypingIndicator agentName={agent.name} agentColor={agent.color} />
        )}
        <div ref={bottomRef} />
      </div>

      {/* input */}
      <div className="px-4 py-3 border-t border-slate-800">
        <div className="flex items-end gap-2 bg-slate-800 rounded-2xl px-3 py-2">

          <FileUpload
            file={file}
            onFile={setFile}
            onClear={() => setFile(null)}
            disabled={loading}
          />

          <textarea
            ref={textareaRef}
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={handleKey}
            disabled={loading}
            placeholder={`Write to ${agent.name}... (Enter = send, Shift+Enter = new line)`}
            rows={1}
            className="flex-1 bg-transparent text-sm text-slate-100 placeholder-slate-600
                       resize-none outline-none py-1.5 max-h-40 overflow-y-auto"
            style={{ lineHeight: '1.5' }}
            onInput={e => {
              e.target.style.height = 'auto'
              e.target.style.height = Math.min(e.target.scrollHeight, 160) + 'px'
            }}
          />

          <button
            onClick={handleSend}
            disabled={loading || (!input.trim() && !file)}
            className="p-2 rounded-xl text-white transition-all disabled:opacity-30"
            style={{ backgroundColor: agent.color }}
          >
            {loading
              ? <RefreshCw size={16} className="animate-spin" />
              : <Send size={16} />}
          </button>
        </div>
        <p className="text-center text-[10px] text-slate-700 mt-2">
          userId: {userId}
        </p>
      </div>
    </div>
  )
}
