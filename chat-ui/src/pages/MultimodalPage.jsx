import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  ArrowLeft, Image as ImageIcon, Mic, Square, Send, Trash2, X, Sparkles,
} from 'lucide-react'
import { sendMultimodal, resetMemory, mediaUrl } from '../api/multimodalApi.js'
import { useRecorder } from '../hooks/useRecorder.js'
import MultimodalDiagram from '../components/MultimodalDiagram.jsx'

/**
 * Multimodal Lab — the one module that exists in a single framework.
 *
 * LAYOUT: chat on the left as the main column, explanation and flow on the
 * right. Patterns Lab puts the description first because there the point IS
 * the comparison — you read, then press Run both. Here the point is the
 * conversation: you attach a photo and talk. The description is reference
 * material, so it sits beside the chat rather than above it, and stays
 * visible while messages accumulate — it is what explains why an answer took
 * three model calls.
 *
 * The conversation id is generated once per page load and is the key of the
 * Redis-backed thread on the server, which is why "reset" calls the backend
 * instead of emptying a local array. Clearing the bubbles without clearing
 * Redis would leave the model remembering things the user can no longer see,
 * and that is the more confusing half of the bug.
 */
export default function MultimodalPage() {
  const [conversationId] = useState(() => 'mm-' + Math.random().toString(36).slice(2, 10))
  const [messages, setMessages] = useState([])
  const [text, setText] = useState('')
  const [image, setImage] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  const recorder = useRecorder()
  const fileRef = useRef(null)
  const bottomRef = useRef(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, busy])

  async function send({ audioBlob = null } = {}) {
    if (busy) return
    if (!text.trim() && !image && !audioBlob) return

    const outgoing = {
      role: 'user',
      text: text.trim(),
      imagePreview: image ? URL.createObjectURL(image) : null,
      voice: !!audioBlob,
    }
    setMessages(m => [...m, outgoing])
    setError(null)
    setBusy(true)

    const payload = { conversationId, question: text.trim(), image, audio: audioBlob }
    setText('')
    setImage(null)
    if (fileRef.current) fileRef.current.value = ''

    try {
      const res = await sendMultimodal(payload)
      setMessages(m => [...m, {
        role: 'assistant',
        text: res.answer,
        media: res.media || [],
        // When the turn started as a voice note the transcript IS the
        // question and the user never saw it — show it, so a mis-heard word
        // reads as a transcription slip rather than as a stupid model.
        transcript: outgoing.voice ? res.question : null,
      }])
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  async function stopAndSend() {
    const blob = await recorder.stop()
    if (blob) await send({ audioBlob: blob })
  }

  async function handleReset() {
    try {
      await resetMemory(conversationId)
      setMessages([])
      setError(null)
    } catch (e) {
      setError(e.message)
    }
  }

  return (
    <div className="flex h-screen bg-slate-950 text-slate-200">

      {/* ── main column: the conversation ─────────────────────────────── */}
      <div className="flex-1 flex flex-col min-w-0">

        <header className="shrink-0 border-b border-slate-800 px-6 py-3
                           flex items-center gap-3">
          <Link to="/" className="flex items-center gap-1.5 text-xs text-slate-400
                                  hover:text-slate-200 transition-colors">
            <ArrowLeft size={14} /> Back to Chat
          </Link>
          <span className="text-slate-700">|</span>
          <Sparkles size={14} className="text-teal-400" />
          <h1 className="text-sm font-medium text-slate-100">Multimodal Lab</h1>
          <span className="text-[10px] text-slate-600">:8089</span>
          <button
            onClick={handleReset}
            className="ml-auto flex items-center gap-1.5 px-3 py-1.5 rounded-lg
                       bg-slate-800 hover:bg-slate-700 text-xs text-slate-300
                       transition-colors"
            title="Clear the Redis-backed conversation"
          >
            <Trash2 size={13} /> Reset memory
          </button>
        </header>

        <div className="flex-1 overflow-y-auto px-6 py-6">
          <div className="max-w-3xl mx-auto">

            {messages.length === 0 && (
              <div className="h-full flex flex-col items-center justify-center
                              text-center py-16">
                <div className="w-14 h-14 rounded-2xl bg-slate-800
                                flex items-center justify-center mb-5">
                  <Sparkles size={24} className="text-teal-400" />
                </div>
                <h2 className="text-base font-semibold text-slate-300 mb-2">
                  Attach a photo, hold the mic, or just type
                </h2>
                <p className="text-sm text-slate-600 max-w-md leading-relaxed">
                  Try: “what is in this picture, then draw it in the style of a
                  1990s football sticker and read me the caption”. That single
                  sentence runs all three tools in a row.
                </p>
              </div>
            )}

            <div className="space-y-4">
              {messages.map((m, i) => <Bubble key={i} message={m} />)}
            </div>

            {busy && (
              <div className="mt-4 text-xs text-slate-500 animate-pulse">
                working — a chained request runs several models in a row…
              </div>
            )}
            <div ref={bottomRef} />

            {error && (
              <div className="mt-4 px-3 py-2 rounded-lg border border-red-900
                              bg-red-950/40 text-xs text-red-300">
                {error}
              </div>
            )}
            {recorder.error && (
              <div className="mt-4 px-3 py-2 rounded-lg border border-amber-900
                              bg-amber-950/30 text-xs text-amber-300">
                {recorder.error}
              </div>
            )}
          </div>
        </div>

        {/* composer */}
        <div className="shrink-0 border-t border-slate-800 px-6 py-4">
          <div className="max-w-3xl mx-auto">

            {image && (
              <div className="mb-2 flex items-center gap-2 text-xs text-slate-400">
                <img
                  src={URL.createObjectURL(image)}
                  alt="attachment preview"
                  className="w-10 h-10 rounded object-cover border border-slate-700"
                />
                <span className="truncate max-w-xs">{image.name}</span>
                <button
                  onClick={() => { setImage(null); if (fileRef.current) fileRef.current.value = '' }}
                  className="text-slate-500 hover:text-slate-300"
                  aria-label="Remove attachment"
                >
                  <X size={14} />
                </button>
              </div>
            )}

            <div className="flex items-end gap-2">
              <input
                ref={fileRef}
                type="file"
                accept="image/*"
                className="hidden"
                onChange={e => setImage(e.target.files?.[0] ?? null)}
              />
              <button
                onClick={() => fileRef.current?.click()}
                disabled={busy}
                className="p-2.5 rounded-xl bg-slate-800 hover:bg-slate-700
                           text-slate-300 disabled:opacity-40 transition-colors"
                title="Attach a picture"
              >
                <ImageIcon size={16} />
              </button>

              {recorder.recording ? (
                <>
                  <button
                    onClick={stopAndSend}
                    className="p-2.5 rounded-xl bg-red-600 hover:bg-red-500
                               text-white transition-colors"
                    title="Stop and send"
                  >
                    <Square size={16} />
                  </button>
                  <span className="text-xs text-red-400 tabular-nums self-center">
                    {String(Math.floor(recorder.seconds / 60)).padStart(2, '0')}:
                    {String(recorder.seconds % 60).padStart(2, '0')}
                  </span>
                  <button
                    onClick={recorder.cancel}
                    className="text-xs text-slate-500 hover:text-slate-300 self-center"
                  >
                    cancel
                  </button>
                </>
              ) : (
                <button
                  onClick={recorder.start}
                  disabled={busy}
                  className="p-2.5 rounded-xl bg-slate-800 hover:bg-slate-700
                             text-slate-300 disabled:opacity-40 transition-colors"
                  title="Record a voice note"
                >
                  <Mic size={16} />
                </button>
              )}

              <textarea
                rows={1}
                value={text}
                onChange={e => setText(e.target.value)}
                onKeyDown={e => {
                  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() }
                }}
                placeholder="Ask something, or describe what to draw…"
                disabled={busy || recorder.recording}
                className="flex-1 resize-none rounded-xl bg-slate-900 border border-slate-800
                           px-3 py-2.5 text-sm text-slate-200 placeholder-slate-600
                           focus:outline-none focus:border-slate-600 disabled:opacity-50"
              />

              <button
                onClick={() => send()}
                disabled={busy || recorder.recording || (!text.trim() && !image)}
                className="p-2.5 rounded-xl bg-teal-600 hover:bg-teal-500 text-white
                           disabled:opacity-30 disabled:hover:bg-teal-600 transition-colors"
                title="Send"
              >
                <Send size={16} />
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* ── right panel: how it works ─────────────────────────────────── */}
      <aside className="w-[440px] shrink-0 border-l border-slate-800
                        overflow-y-auto hidden xl:block">
        <div className="px-5 py-5">

          <div className="rounded-xl border border-slate-800 bg-slate-950/60 p-4 mb-5">
            <p className="text-[10px] tracking-wider text-slate-600 mb-3">FLOW</p>
            <MultimodalDiagram />
          </div>

          <h2 className="text-base font-semibold text-slate-100 mb-1">
            One router, four models
          </h2>
          <p className="text-xs text-slate-500 mb-4">
            Spring AI only — this is where the framework comparison stops.
          </p>

          <p className="text-[13px] text-slate-300 leading-relaxed mb-3">
            Every modality is a tool, so the routing logic is the system
            prompt: no classifier, no switch. Ask for a picture and the model
            calls <Code>generate_image</Code>; ask to hear something and it
            calls <Code>speak_text</Code>. Attach a photo and it calls{' '}
            <Code>analyze_image</Code>, which hands the job to a second agent
            with its own prompt and its own vision model.
          </p>

          <p className="text-[13px] text-slate-300 leading-relaxed mb-3">
            The chaining is free. Tool execution lives in{' '}
            <Code>ToolCallingAdvisor</Code>, which keeps calling the model
            until it stops asking for tools — three round trips with no loop in
            our code. Compare with Patterns Lab, where the loop is
            hand-written: same iteration, but there{' '}
            <em className="text-slate-400">we</em> own the plan and here the{' '}
            <em className="text-slate-400">model</em> does.
          </p>

          <p className="text-[13px] text-slate-300 leading-relaxed mb-5">
            The router never sees a pixel — it gets a URL and decides whether
            looking is worth a vision call, which keeps it on a cheap model.
            The cost is real: the description is a bottleneck, so a follow-up
            question about the same photo needs a second vision call.
          </p>

          <div className="space-y-3 text-xs border-t border-slate-800 pt-4">
            <Fact label="Voice in">
              transcribed before the router — a voice note is the question, not
              something the model can choose to read
            </Fact>
            <Fact label="Artifacts">
              written to disk, returned as URLs; base64 would land in the
              context, the spans and Loki at once
            </Fact>
            <Fact label="Memory">
              Redis, not a field — the files outlive a restart, so the thread
              should too
            </Fact>
            <Fact label="Cost">
              tokens stop describing it; images bill per picture, speech per
              character
            </Fact>
          </div>

          <p className="text-[11px] text-slate-600 mt-5 leading-relaxed">
            Tempo: one short router chat, then a tool span per modality — each
            with its own nested chat. A chained request can take half a minute.
          </p>
        </div>
      </aside>
    </div>
  )
}

const Code = ({ children }) => (
  <code className="px-1 py-0.5 rounded bg-slate-800 text-teal-300 text-[11px]">
    {children}
  </code>
)

function Fact({ label, children }) {
  return (
    <div>
      <p className="text-slate-300 font-medium mb-0.5">{label}</p>
      <p className="text-slate-500 leading-relaxed">{children}</p>
    </div>
  )
}

function Bubble({ message }) {
  const mine = message.role === 'user'

  return (
    <div className={`flex ${mine ? 'justify-end' : 'justify-start'}`}>
      <div className={`max-w-[80%] rounded-2xl px-4 py-3 border
                       ${mine ? 'bg-indigo-600/20 border-indigo-800'
                              : 'bg-slate-900 border-slate-800'}`}>

        {message.imagePreview && (
          <img
            src={message.imagePreview}
            alt="attached"
            className="mb-2 rounded-lg max-h-56 border border-slate-700"
          />
        )}

        {message.voice && !message.text && (
          <p className="text-[11px] text-slate-500 italic mb-1">voice note</p>
        )}

        {message.transcript && (
          <p className="text-[11px] text-slate-500 italic mb-2">
            heard: “{message.transcript}”
          </p>
        )}

        {message.text && (
          <p className="text-sm whitespace-pre-wrap leading-relaxed">{message.text}</p>
        )}

        {message.media?.map(path => (
          <div key={path} className="mt-3">
            {path.endsWith('.mp3') ? (
              <audio controls src={mediaUrl(path)} className="w-full max-w-sm" />
            ) : (
              <a href={mediaUrl(path)} target="_blank" rel="noreferrer">
                <img
                  src={mediaUrl(path)}
                  alt="generated"
                  className="rounded-lg max-h-80 border border-slate-700"
                />
              </a>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}
