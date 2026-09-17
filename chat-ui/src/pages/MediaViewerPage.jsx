import { useSearchParams, Link } from 'react-router-dom'
import { ArrowLeft, Download, ExternalLink } from 'lucide-react'
import { useEffect, useState } from 'react'
import { mediaUrl } from '../api/multimodalApi.js'

/**
 * Single-artifact viewer.
 *
 * WHY A PAGE AND NOT A MODAL. These artifacts are the point of the module and
 * some of them need room: a 1024px picture, a video, a set of lyrics. A modal
 * over a chat gives them a third of the screen and traps them there — you
 * cannot link to one, cannot leave it open on a second monitor, cannot show it
 * full-bleed on a projector. A route can do all three.
 *
 * STATE TRAVELS IN THE URL, not in router state. Navigating with `state`
 * works until someone reloads the page or pastes the link to a colleague, at
 * which point the viewer renders an empty box and looks broken. Query params
 * survive both.
 *
 * The file itself is served by multimodal-lab with Content-Disposition:
 * inline, so "open the raw file" hands it to the browser's own viewer rather
 * than downloading it — useful on stage when you want the picture without the
 * chrome around it.
 */
export default function MediaViewerPage() {
  const [params] = useSearchParams()
  const url = params.get('url')
  const kind = (params.get('kind') || '').toLowerCase()
  const prompt = params.get('prompt') || ''
  const conversationId = params.get('conversationId') || ''

  const src = mediaUrl(url)
  const [text, setText] = useState(null)

  // A .txt artifact has to be fetched to be shown — everything else is an
  // element the browser renders from the URL alone.
  useEffect(() => {
    if (kind !== 'text' || !src) return
    let cancelled = false
    fetch(src)
      .then(r => r.text())
      .then(t => { if (!cancelled) setText(t) })
      .catch(() => { if (!cancelled) setText('(could not load the file)') })
    return () => { cancelled = true }
  }, [src, kind])

  if (!url) {
    return (
      <div className="min-h-screen bg-slate-950 text-slate-300 flex items-center justify-center">
        <p className="text-sm">Nothing to show.</p>
      </div>
    )
  }

  const filename = url.substring(url.lastIndexOf('/') + 1)

  return (
    <div className="min-h-screen bg-slate-950 text-slate-200 flex flex-col">

      <header className="shrink-0 border-b border-slate-800 px-6 py-3 flex items-center gap-3">
        <Link
          to={`/multimodal${conversationId ? `?conversationId=${encodeURIComponent(conversationId)}` : ''}`}
          className="flex items-center gap-1.5 text-xs text-slate-400 hover:text-slate-200 transition-colors"
        >
          <ArrowLeft size={14} /> Back to the conversation
        </Link>
        <span className="text-slate-700">|</span>
        <span className="text-xs text-slate-500">{KIND_LABEL[kind] || 'File'}</span>
        <code className="text-[10px] text-slate-600">{filename}</code>

        <div className="ml-auto flex items-center gap-2">
          <a href={src} target="_blank" rel="noreferrer"
             className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-slate-800
                        hover:bg-slate-700 text-xs text-slate-300 transition-colors">
            <ExternalLink size={13} /> Open raw file
          </a>
          <a href={src} download={filename}
             className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-slate-800
                        hover:bg-slate-700 text-xs text-slate-300 transition-colors">
            <Download size={13} /> Download
          </a>
        </div>
      </header>

      <main className="flex-1 overflow-y-auto px-6 py-8">
        <div className="max-w-4xl mx-auto">

          <div className="rounded-2xl border border-slate-800 bg-slate-900/40 p-6
                          flex items-center justify-center min-h-[40vh]">
            {(kind === 'image' || kind === 'upload') && (
              <img src={src} alt={prompt} className="max-h-[70vh] rounded-lg" />
            )}

            {kind === 'video' && (
              <video src={src} controls className="max-h-[70vh] w-full rounded-lg" />
            )}

            {(kind === 'music' || kind === 'speech') && (
              <div className="w-full max-w-xl text-center">
                <audio src={src} controls className="w-full" />
                <p className="mt-3 text-[11px] text-slate-600">
                  {kind === 'music'
                    ? 'Composed by a music model (Lyria)'
                    : 'Read out loud by a text-to-speech model'}
                </p>
              </div>
            )}

            {kind === 'text' && (
              <pre className="w-full whitespace-pre-wrap text-sm text-slate-300 leading-relaxed">
                {text ?? 'loading…'}
              </pre>
            )}
          </div>

          {prompt && (
            <div className="mt-5">
              <p className="text-[10px] tracking-wider text-slate-600 mb-1.5">
                WHAT THE MODEL WAS ASKED FOR
              </p>
              <p className="text-sm text-slate-400 leading-relaxed whitespace-pre-wrap">
                {prompt}
              </p>
            </div>
          )}
        </div>
      </main>
    </div>
  )
}

const KIND_LABEL = {
  image: 'Image',
  upload: 'Uploaded image',
  video: 'Video',
  music: 'Music',
  speech: 'Narration',
  text: 'Text',
}
