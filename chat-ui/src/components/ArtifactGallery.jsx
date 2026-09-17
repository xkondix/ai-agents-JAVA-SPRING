import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  Image as ImageIcon, Music, Mic, Film, FileText, Upload, RefreshCw, AlertTriangle,
} from 'lucide-react'
import { mediaUrl } from '../api/multimodalApi.js'

/**
 * The gallery — everything the lab has produced, by kind.
 *
 * WHY IT IS NOT JUST THE CHAT SCROLLBACK. Artifacts outlive the thread: they
 * are files on disk, they survive a restart, and "Reset memory" deliberately
 * does not delete them. After twenty turns the picture you want is somewhere
 * above the fold in a conversation that also contains two songs and a
 * transcript.
 *
 * DEFAULT SCOPE IS "ALL". The conversation id is regenerated on every page
 * load, so a per-thread gallery would go blank after a refresh while the
 * files sat right there in ./media.
 *
 * KIND COMES FROM THE SERVER, NOT FROM THE EXTENSION. A narration mp3 and a
 * Lyria song are the same file format and completely different things to a
 * listener. The backend tags each artifact when it is created; only files
 * found by the disk scan fall back to guessing from the extension.
 *
 * ── AN EMPTY GALLERY AND A BROKEN ONE MUST NOT LOOK THE SAME ───────────────
 *
 * The first version caught every fetch error and left the list empty, on the
 * grounds that a gallery is a convenience and should never put an error
 * banner over a working conversation. That was half right. The other half is
 * that "Nothing in ./media yet" is a CLAIM ABOUT THE SERVER, and making it
 * without having heard from the server is exactly the kind of confident
 * wrong answer this whole project keeps running into: a failure rendered as
 * a normal, reassuring state.
 *
 * It bit immediately. With nineteen files on disk the panel calmly reported
 * an empty directory, and the only way to tell the difference was to open
 * devtools. So the failure now says so, quietly, in the panel — still no
 * modal, still nothing over the chat, but no longer a lie.
 */

const KINDS = [
  { id: 'image',  label: 'Images',  icon: ImageIcon },
  { id: 'video',  label: 'Video',   icon: Film },
  { id: 'music',  label: 'Music',   icon: Music },
  { id: 'speech', label: 'Speech',  icon: Mic },
  { id: 'text',   label: 'Text',    icon: FileText },
  { id: 'upload', label: 'Uploads', icon: Upload },
]

export default function ArtifactGallery({ conversationId, refreshKey }) {
  const [artifacts, setArtifacts] = useState([])
  const [active, setActive] = useState('image')
  const [scope, setScope] = useState('all')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  useEffect(() => {
    let cancelled = false
    async function load() {
      setLoading(true)
      setError(null)
      try {
        const res = await fetch(
          'http://localhost:8089/api/v1/multimodal/artifacts'
          + `?conversationId=${encodeURIComponent(conversationId)}&scope=${scope}`)

        if (!res.ok) {
          if (!cancelled) setError(`server answered ${res.status}`)
          return
        }
        const data = await res.json()
        if (!cancelled) setArtifacts(data)
      } catch (e) {
        // Module down, CORS, DNS — all indistinguishable from here, and all
        // meaning the same thing to the user: this list is not the truth.
        if (!cancelled) setError('multimodal-lab is not answering on :8089')
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    load()
    return () => { cancelled = true }
  }, [conversationId, refreshKey, scope])

  const counts = Object.fromEntries(
    KINDS.map(k => [k.id, artifacts.filter(a => a.kind?.toLowerCase() === k.id).length]))

  const visible = KINDS.filter(k => counts[k.id] > 0)
  const shown = artifacts.filter(a => a.kind?.toLowerCase() === active)

  // Keep the active tab pointing at something that exists — the first
  // artifact in view is rarely an image.
  useEffect(() => {
    if (visible.length > 0 && counts[active] === 0) {
      setActive(visible[0].id)
    }
  }, [artifacts]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <aside className="w-60 shrink-0 border-r border-slate-800 flex flex-col bg-slate-950">

      <div className="px-4 pt-3 pb-2 border-b border-slate-800">
        <div className="flex items-center gap-2 mb-2">
          <p className="text-xs font-medium text-slate-300">Generated</p>
          {loading && <RefreshCw size={11} className="text-slate-600 animate-spin" />}
          {!loading && !error && (
            <span className="ml-auto text-[10px] text-slate-600">{artifacts.length}</span>
          )}
        </div>

        <div className="flex gap-1">
          <ScopeButton active={scope === 'all'} onClick={() => setScope('all')}>
            Everything
          </ScopeButton>
          <ScopeButton active={scope === 'conversation'} onClick={() => setScope('conversation')}>
            This chat
          </ScopeButton>
        </div>
      </div>

      {error ? (
        <div className="px-4 py-5">
          <div className="flex items-start gap-2 text-[11px] text-amber-400/90">
            <AlertTriangle size={13} className="shrink-0 mt-0.5" />
            <div>
              <p className="font-medium">Gallery unavailable</p>
              <p className="text-amber-500/70 mt-0.5 leading-relaxed">{error}</p>
              <p className="text-slate-600 mt-2 leading-relaxed">
                Files on disk are unaffected — this list could not be loaded,
                which is not the same as it being empty.
              </p>
            </div>
          </div>
        </div>
      ) : artifacts.length === 0 ? (
        <p className="px-4 py-6 text-[11px] text-slate-600 leading-relaxed">
          {scope === 'all'
            ? 'Nothing in ./media yet. Anything the agent creates shows up here and survives a memory reset.'
            : 'This conversation has not made anything yet — switch to “Everything” for older files.'}
        </p>
      ) : (
        <>
          <div className="flex flex-wrap gap-1 px-3 py-2 border-b border-slate-800">
            {visible.map(k => {
              const Icon = k.icon
              return (
                <button
                  key={k.id}
                  onClick={() => setActive(k.id)}
                  className={`flex items-center gap-1 px-2 py-1 rounded-md text-[10px]
                              transition-colors ${active === k.id
                                ? 'bg-teal-600/20 text-teal-300 border border-teal-800'
                                : 'text-slate-500 hover:text-slate-300 border border-transparent'}`}
                >
                  <Icon size={11} /> {k.label}
                  <span className="text-slate-600">{counts[k.id]}</span>
                </button>
              )
            })}
          </div>

          <div className="flex-1 overflow-y-auto px-3 py-3 space-y-2">
            {shown.map(a => (
              <Entry key={a.url} artifact={a} conversationId={conversationId} />
            ))}
          </div>
        </>
      )}
    </aside>
  )
}

function ScopeButton({ active, onClick, children }) {
  return (
    <button
      onClick={onClick}
      className={`flex-1 px-2 py-1 rounded-md text-[10px] transition-colors border
                  ${active
                    ? 'bg-slate-800 text-slate-200 border-slate-700'
                    : 'text-slate-500 hover:text-slate-300 border-transparent'}`}
    >
      {children}
    </button>
  )
}

function Entry({ artifact, conversationId }) {
  const kind = artifact.kind?.toLowerCase()
  const src = mediaUrl(artifact.url)

  const to = `/multimodal/view?url=${encodeURIComponent(artifact.url)}`
    + `&kind=${encodeURIComponent(kind || '')}`
    + `&prompt=${encodeURIComponent(artifact.prompt || '')}`
    + `&conversationId=${encodeURIComponent(conversationId)}`

  return (
    <Link
      to={to}
      className="block rounded-lg border border-slate-800 bg-slate-900/60 p-2
                 hover:border-slate-600 transition-colors"
    >
      {(kind === 'image' || kind === 'upload') && (
        <img src={src} alt={artifact.prompt || ''} loading="lazy"
             className="w-full rounded-md border border-slate-800" />
      )}

      {kind === 'video' && (
        // No inline playback in the list: a thumbnail-sized <video> with
        // controls is unusable, and six of them loading at once is worse.
        <div className="w-full aspect-video rounded-md border border-slate-800
                        bg-slate-950 flex items-center justify-center">
          <Film size={20} className="text-slate-600" />
        </div>
      )}

      {(kind === 'music' || kind === 'speech') && (
        <div className="flex items-center gap-2 py-1">
          {kind === 'music'
            ? <Music size={14} className="text-teal-400 shrink-0" />
            : <Mic size={14} className="text-sky-400 shrink-0" />}
          <span className="text-[11px] text-slate-400">
            {kind === 'music' ? 'track' : 'narration'}
          </span>
        </div>
      )}

      {kind === 'text' && (
        <div className="flex items-center gap-2 py-1">
          <FileText size={14} className="text-slate-500 shrink-0" />
          <span className="text-[11px] text-slate-400">text</span>
        </div>
      )}

      {artifact.prompt ? (
        // The prompt is the only thing that makes a gallery of twelve
        // pictures navigable once the conversation has scrolled away.
        <p className="mt-1.5 text-[10px] text-slate-500 leading-snug line-clamp-2">
          {artifact.prompt}
        </p>
      ) : (
        // Found on disk with no index entry — an older run, or a conversation
        // whose id is long gone. Say so rather than showing a blank line.
        <p className="mt-1.5 text-[10px] text-slate-700 italic">from an earlier session</p>
      )}
    </Link>
  )
}
