import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ArrowLeft, Image as ImageIcon, Send, Trash2, X, Sparkles } from 'lucide-react'
import { sendMultimodal, resetMemory, mediaUrl } from '../api/multimodalApi.js'
import MultimodalDiagram from '../components/MultimodalDiagram.jsx'
import ArtifactGallery from '../components/ArtifactGallery.jsx'

/**
 * Multimodal Lab — the one module that exists in a single framework.
 *
 * THREE COLUMNS, EACH ANSWERING A DIFFERENT QUESTION:
 *   left   — what has been made so far (gallery, click opens the viewer page)
 *   centre — the conversation
 *   right  — how it works (flow + explanation)
 *
 * The gallery is on the left rather than folded into the chat because the
 * artifacts outlive the thread: files stay on disk, survive a restart, and
 * "Reset memory" deliberately does not remove them.
 *
 * Artifacts still render INLINE in the bubbles as well. That is not
 * duplication: in the conversation they are the answer to a question, in the
 * gallery they are a catalogue.
 *
 * ── THE MICROPHONE IS GONE, AND THE HOOK IS STILL HERE ─────────────────────
 *
 * Speech-to-text was the one capability of the five that never worked end to
 * end. The backend path is complete and correct — TranscriptionService,
 * multipart upload, transcript-becomes-the-question — but the OpenRouter
 * model slug was a guess, made with the same reasoning that produced two
 * non-existent TTS names earlier, and the catalogue query for transcription
 * models never returned anything to check it against. It answered 500.
 *
 * So the button is out of the composer rather than left there to fail in
 * front of an audience. What is NOT done is deleting the backend: the reason
 * transcription cannot be a tool (a voice note IS the question, so the model
 * would have to know its contents in order to decide to read it) is one of
 * the clearer points this module makes, and the code that demonstrates it is
 * worth more in the repository than the button was in the UI.
 *
 * useRecorder.js is likewise kept. It solves a real browser problem —
 * MediaRecorder's container format varies by browser and the stream tracks
 * have to be stopped explicitly or the tab keeps its recording indicator lit
 * — and none of that knowledge is worth re-deriving when the model name is
 * eventually confirmed. Re-enabling is: import the hook, put the two buttons
 * back, pass `audio` to sendMultimodal.
 */
export default function MultimodalPage() {
  const [params] = useSearchParams()
  const [conversationId] = useState(
    () => params.get('conversationId') || 'mm-' + Math.random().toString(36).slice(2, 10))

  const [messages, setMessages] = useState([])
  const [text, setText] = useState('')
  const [image, setImage] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  // Bumped after every turn so the gallery refetches — cheaper and more
  // predictable than polling, and the only moment new artifacts can appear.
  const [galleryKey, setGalleryKey] = useState(0)

  const fileRef = useRef(null)
  const bottomRef = useRef(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, busy])

  async function send() {
    if (busy) return
    if (!text.trim() && !image) return

    const outgoing = {
      role: 'user',
      text: text.trim(),
      imagePreview: image ? URL.createObjectURL(image) : null,
    }
    setMessages(m => [...m, outgoing])
    setError(null)
    setBusy(true)

    const payload = { conversationId, question: text.trim(), image }
    setText('')
    setImage(null)
    if (fileRef.current) fileRef.current.value = ''

    try {
      const res = await sendMultimodal(payload)
      setMessages(m => [...m, {
        role: 'assistant',
        text: res.answer,
        media: res.media || [],
      }])
      setGalleryKey(k => k + 1)
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  async function handleReset() {
    try {
      await resetMemory(conversationId)
      setMessages([])
      setError(null)
      // The gallery is NOT cleared here — see ArtifactRegistry.
    } catch (e) {
      setError(e.message)
    }
  }

  return (
    <div className="flex h-screen bg-slate-950 text-slate-200">

      {/* ── left: what has been made ──────────────────────────────────── */}
      <ArtifactGallery conversationId={conversationId} refreshKey={galleryKey} />

      {/* ── centre: the conversation ──────────────────────────────────── */}
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
            title="Clears the model's memory — files and the gallery are kept"
          >
            <Trash2 size={13} /> Reset memory
          </button>
        </header>

        <div className="flex-1 overflow-y-auto px-6 py-6">
          <div className="max-w-3xl mx-auto">

            {messages.length === 0 && (
              <div className="flex flex-col items-center justify-center text-center py-16">
                <div className="w-14 h-14 rounded-2xl bg-slate-800
                                flex items-center justify-center mb-5">
                  <Sparkles size={24} className="text-teal-400" />
                </div>
                <h2 className="text-base font-semibold text-slate-300 mb-2">
                  Attach a photo, or just type
                </h2>
                <p className="text-sm text-slate-600 max-w-md leading-relaxed">
                  Try: “what is in this picture, then draw it in the style of a
                  1990s football sticker and write a short chant about it”. That
                  single sentence runs three tools in a row.
                </p>
              </div>
            )}

            <div className="space-y-4">
              {messages.map((m, i) => (
                <Bubble key={i} message={m} conversationId={conversationId} />
              ))}
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

              <textarea
                rows={1}
                value={text}
                onChange={e => setText(e.target.value)}
                onKeyDown={e => {
                  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() }
                }}
                placeholder="Ask something, or describe what to draw…"
                disabled={busy}
                className="flex-1 resize-none rounded-xl bg-slate-900 border border-slate-800
                           px-3 py-2.5 text-sm text-slate-200 placeholder-slate-600
                           focus:outline-none focus:border-slate-600 disabled:opacity-50"
              />

              <button
                onClick={send}
                disabled={busy || (!text.trim() && !image)}
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

      {/* ── right: how it works ───────────────────────────────────────── */}
      <aside className="w-[400px] shrink-0 border-l border-slate-800
                        overflow-y-auto hidden xl:block">
        <div className="px-5 py-5">

          <div className="rounded-xl border border-slate-800 bg-slate-950/60 p-4 mb-5">
            <p className="text-[10px] tracking-wider text-slate-600 mb-3">FLOW</p>
            <MultimodalDiagram />
          </div>

          <h2 className="text-base font-semibold text-slate-100 mb-1">
            One router, many models
          </h2>
          <p className="text-xs text-slate-500 mb-4">
            Spring AI only — this is where the framework comparison stops.
          </p>

          <p className="text-[13px] text-slate-300 leading-relaxed mb-3">
            Every modality is a tool, so the routing logic is the system
            prompt: no classifier, no switch. Ask for a picture and the model
            calls <Code>generate_image</Code>; ask for a song and it calls{' '}
            <Code>generate_music</Code>; attach a photo and it calls{' '}
            <Code>analyze_image</Code>, which hands the job to a second agent
            with its own prompt and its own vision model.
          </p>

          <p className="text-[13px] text-slate-300 leading-relaxed mb-3">
            The chaining is free. Tool execution lives in{' '}
            <Code>ToolCallingAdvisor</Code>, which keeps calling the model
            until it stops asking for tools — several round trips with no loop
            in our code. Compare with Patterns Lab, where the loop is
            hand-written: same iteration, but there{' '}
            <em className="text-slate-400">we</em> own the plan and here the{' '}
            <em className="text-slate-400">model</em> does.
          </p>

          <p className="text-[13px] text-slate-300 leading-relaxed mb-5">
            The router never sees a pixel — it gets a URL and decides whether
            looking is worth a vision call, which keeps it on a cheap model.
            The cost is real: the description is a bottleneck, and the router
            cannot check it, because it has never seen the image.
          </p>

          <div className="space-y-3 text-xs border-t border-slate-800 pt-4">
            <Fact label="Two models, on purpose">
              the router is gpt-4o-mini and the vision leg gpt-4o — you pay the
              premium only on calls that actually look at a picture
            </Fact>
            <Fact label="Artifacts">
              written to disk, returned as URLs, indexed in the gallery on the
              left; base64 would land in the context, the spans and Loki at once
            </Fact>
            <Fact label="Memory">
              Redis, not a field — the files outlive a restart, so the thread
              should too. Resetting memory keeps the gallery
            </Fact>
            <Fact label="Cost">
              tokens stop describing it: images bill per picture, speech per
              character, a Lyria song $0.08 flat, four seconds of video $1.60
            </Fact>
            <Fact label="Not here">
              voice input is built but switched off — the transcription model
              slug was never confirmed and it answered 500
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

function Bubble({ message, conversationId }) {
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

        {message.text && (
          <p className="text-sm whitespace-pre-wrap leading-relaxed">{message.text}</p>
        )}

        {message.media?.map(path => (
          <InlineMedia key={path} path={path} conversationId={conversationId} />
        ))}
      </div>
    </div>
  )
}

/**
 * Inline rendering in the bubble. Kind is inferred from the extension here —
 * the chat response returns URLs only — which is good enough to pick an
 * element, though not good enough to tell a song from narration. The gallery
 * uses the server's tag for that.
 */
function InlineMedia({ path, conversationId }) {
  const src = mediaUrl(path)
  const to = `/multimodal/view?url=${encodeURIComponent(path)}`
    + `&conversationId=${encodeURIComponent(conversationId)}`
    + `&kind=${path.endsWith('.mp3') ? 'music' : path.endsWith('.mp4') ? 'video' : 'image'}`

  if (path.endsWith('.mp3')) {
    return <audio controls src={src} className="mt-3 w-full max-w-sm" />
  }
  if (path.endsWith('.mp4')) {
    return <video controls src={src} className="mt-3 rounded-lg max-h-80 w-full" />
  }
  return (
    <Link to={to} className="block mt-3">
      <img src={src} alt="generated"
           className="rounded-lg max-h-80 border border-slate-700
                      hover:border-slate-500 transition-colors" />
    </Link>
  )
}
