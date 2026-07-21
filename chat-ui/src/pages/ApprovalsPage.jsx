import { useState, useEffect } from 'react'
import { Link } from 'react-router-dom'
import {
  ShieldCheck, ArrowLeft, RefreshCw, Clock,
  CheckCircle, XCircle, FileText, AlertTriangle,
  Trash2, FilePlus, FolderInput, StickyNote, EyeOff
} from 'lucide-react'
import { usePendingApprovals } from '../hooks/usePendingApprovals.js'
import { approveOperation, rejectOperation, APPROVAL_SOURCES } from '../api/approvalsApi.js'

// ── Tool type config ───────────────────────────────────────────────────────
const TOOL_CONFIG = {
  // mcp-server knowledge base operations
  SAVE_NOTE:   { label: 'Save Note',     icon: StickyNote,  color: 'text-amber-400',  bg: 'bg-amber-500/10',  border: 'border-amber-500/30'  },
  DELETE_NOTE: { label: 'Delete Note',   icon: Trash2,      color: 'text-red-400',    bg: 'bg-red-500/10',    border: 'border-red-500/30'    },
  // patterns modules — confidential data disclosure
  SECRET_RUMORS: { label: 'Disclose Secret Rumors', icon: EyeOff, color: 'text-fuchsia-400', bg: 'bg-fuchsia-500/10', border: 'border-fuchsia-500/30' },
  // code-mcp-server file operations (reference)
  WRITE_FILE:  { label: 'Write File',    icon: FileText,    color: 'text-amber-400',  bg: 'bg-amber-500/10',  border: 'border-amber-500/30'  },
  CREATE_FILE: { label: 'Create File',   icon: FilePlus,    color: 'text-blue-400',   bg: 'bg-blue-500/10',   border: 'border-blue-500/30'   },
  MOVE_FILE:   { label: 'Move / Rename', icon: FolderInput, color: 'text-purple-400', bg: 'bg-purple-500/10', border: 'border-purple-500/30' },
  DELETE_FILE: { label: 'Delete File',   icon: Trash2,      color: 'text-red-400',    bg: 'bg-red-500/10',    border: 'border-red-500/30'    },
}

const DEFAULT_CONFIG = {
  label: 'Operation', icon: ShieldCheck,
  color: 'text-slate-400', bg: 'bg-slate-500/10', border: 'border-slate-500/30'
}

// Types that are irreversible / destructive
const DESTRUCTIVE_TYPES = new Set(['DELETE_NOTE', 'DELETE_FILE'])

const STATUS_CONFIG = {
  APPROVED: { color: 'text-green-400', bg: 'bg-green-500/10', label: 'Approved' },
  REJECTED: { color: 'text-red-400',   bg: 'bg-red-500/10',   label: 'Rejected' },
  TIMEOUT:  { color: 'text-slate-400', bg: 'bg-slate-500/10', label: 'Timeout'  },
}

// ── Live elapsed timer ─────────────────────────────────────────────────────
function useElapsed(createdAt) {
  const [elapsed, setElapsed] = useState(0)
  useEffect(() => {
    const start = new Date(createdAt).getTime()
    const tick = () => setElapsed(Math.round((Date.now() - start) / 1000))
    tick()
    const timer = setInterval(tick, 1000)
    return () => clearInterval(timer)
  }, [createdAt])
  return elapsed
}

// ── Approval Card ──────────────────────────────────────────────────────────
function ApprovalCard({ approval, onApprove, onReject, busy }) {
  const [expanded, setExpanded] = useState(true)
  const cfg      = TOOL_CONFIG[approval.type] ?? DEFAULT_CONFIG
  const Icon     = cfg.icon
  const isDelete = DESTRUCTIVE_TYPES.has(approval.type)
  const elapsed  = useElapsed(approval.createdAt)

  const formatElapsed = (s) => {
    if (s < 60)   return `${s}s`
    if (s < 3600) return `${Math.floor(s / 60)}m ${s % 60}s`
    return `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`
  }

  // Warn when approaching timeout (10 min = 600s)
  const nearTimeout = elapsed > 480
  const timerColor  = elapsed > 540 ? 'text-red-400' : nearTimeout ? 'text-amber-400' : 'text-slate-500'

  return (
    <div className={`rounded-2xl border ${cfg.border} ${cfg.bg} overflow-hidden`}>

      {/* Header */}
      <div className="flex items-start justify-between px-5 py-4">
        <div className="flex items-center gap-3">
          <div className={`w-9 h-9 rounded-xl flex items-center justify-center ${cfg.bg} border ${cfg.border}`}>
            <Icon size={16} className={cfg.color} />
          </div>
          <div>
            <div className="flex items-center gap-2 flex-wrap">
              <p className={`text-sm font-semibold ${cfg.color}`}>{cfg.label}</p>
              {isDelete && (
                <span className="text-[10px] font-bold bg-red-500 text-white px-1.5 py-0.5 rounded">
                  IRREVERSIBLE
                </span>
              )}
              {approval.sourceLabel && (
                <span className="text-[10px] font-mono text-slate-400 bg-slate-800
                                 px-1.5 py-0.5 rounded border border-slate-700">
                  {approval.sourceLabel}
                </span>
              )}
            </div>
            <p className="text-xs text-slate-400 mt-0.5">{approval.description}</p>
          </div>
        </div>
        <div className={`flex items-center gap-1.5 text-xs shrink-0 ${timerColor}`}>
          <Clock size={11} />
          <span className="font-mono">{formatElapsed(elapsed)}</span>
          <span className="text-slate-700 font-mono text-[10px] ml-1">#{approval.id}</span>
        </div>
      </div>

      {/* Details */}
      {approval.details && (
        <div className="px-5 pb-4">
          <button
            onClick={() => setExpanded(e => !e)}
            className="text-[11px] text-slate-500 hover:text-slate-300 mb-2 flex items-center gap-1"
          >
            {expanded ? '▼' : '▶'} Details
          </button>
          {expanded && (
            <pre className="text-xs text-slate-300 bg-slate-900/60 rounded-xl p-4
                            overflow-x-auto whitespace-pre-wrap font-mono leading-relaxed
                            border border-slate-700/50 max-h-64">
              {approval.details}
            </pre>
          )}
        </div>
      )}

      {/* Actions */}
      <div className="flex gap-3 px-5 pb-5">
        <button
          disabled={busy}
          onClick={() => onReject(approval)}
          className="flex-1 flex items-center justify-center gap-2 py-2.5 px-4
                     rounded-xl border border-slate-600 text-slate-400
                     hover:border-red-500/50 hover:text-red-400 hover:bg-red-500/5
                     transition-all text-sm font-medium disabled:opacity-40"
        >
          <XCircle size={15} />
          Reject
        </button>
        <button
          disabled={busy}
          onClick={() => onApprove(approval)}
          className={`flex-1 flex items-center justify-center gap-2 py-2.5 px-6
                     rounded-xl text-white font-medium text-sm transition-all
                     disabled:opacity-40
                     ${isDelete
                       ? 'bg-red-600 hover:bg-red-500'
                       : 'bg-emerald-600 hover:bg-emerald-500'}`}
        >
          <CheckCircle size={15} />
          {isDelete ? 'Confirm Delete' : 'Approve'}
        </button>
      </div>
    </div>
  )
}

// ── History Card ───────────────────────────────────────────────────────────
function HistoryCard({ item }) {
  const toolCfg   = TOOL_CONFIG[item.type] ?? DEFAULT_CONFIG
  const statusCfg = STATUS_CONFIG[item.status] ?? STATUS_CONFIG.TIMEOUT
  const Icon = toolCfg.icon
  return (
    <div className="flex items-center gap-3 px-4 py-3 rounded-xl bg-slate-900/40 border border-slate-800">
      <Icon size={14} className={toolCfg.color} />
      <div className="flex-1 min-w-0">
        <p className="text-xs text-slate-300 truncate">{item.description}</p>
        <p className="text-[10px] text-slate-600 font-mono">
          #{item.id}{item.sourceLabel ? ` · ${item.sourceLabel}` : ''}
        </p>
      </div>
      <span className={`text-[10px] font-bold px-2 py-0.5 rounded-full ${statusCfg.color} ${statusCfg.bg}`}>
        {statusCfg.label}
      </span>
    </div>
  )
}

// ── Main Page ──────────────────────────────────────────────────────────────
export default function ApprovalsPage() {
  const { approvals, count, refresh, loading } = usePendingApprovals()
  const [history, setHistory]   = useState([])
  const [busy, setBusy]         = useState(false)
  const [notification, setNote] = useState(null)

  const notify = (msg, type = 'success') => {
    setNote({ msg, type })
    setTimeout(() => setNote(null), 3000)
  }

  const handleApprove = async (approval) => {
    setBusy(true)
    try {
      await approveOperation(approval.id, approval.source)
      setHistory(h => [{ ...approval, status: 'APPROVED' }, ...h].slice(0, 20))
      notify('Operation approved successfully')
      await refresh()
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  const handleReject = async (approval) => {
    setBusy(true)
    try {
      await rejectOperation(approval.id, approval.source)
      setHistory(h => [{ ...approval, status: 'REJECTED' }, ...h].slice(0, 20))
      notify('Operation rejected')
      await refresh()
    } catch (e) {
      notify(e.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100">

      {/* Notification toast */}
      {notification && (
        <div className={`fixed top-4 right-4 z-50 px-4 py-3 rounded-xl text-sm font-medium shadow-xl
                        ${notification.type === 'error'
                          ? 'bg-red-600 text-white'
                          : 'bg-emerald-600 text-white'}`}>
          {notification.msg}
        </div>
      )}

      {/* Header */}
      <div className="border-b border-slate-800 px-6 py-4">
        <div className="max-w-3xl mx-auto flex items-center justify-between">
          <div className="flex items-center gap-4">
            <Link
              to="/"
              className="flex items-center gap-1.5 text-slate-500 hover:text-slate-300 transition-colors text-sm"
            >
              <ArrowLeft size={14} />
              Back to Chat
            </Link>
            <div className="w-px h-4 bg-slate-700" />
            <div className="flex items-center gap-2">
              <ShieldCheck size={16} className="text-amber-400" />
              <h1 className="font-semibold text-slate-100">Approval Flow</h1>
            </div>
          </div>
          <div className="flex items-center gap-3">
            {count > 0 && (
              <span className="text-xs font-bold bg-amber-500 text-white px-2 py-0.5 rounded-full animate-pulse">
                {count} pending
              </span>
            )}
            <button
              onClick={refresh}
              disabled={loading}
              className="p-2 rounded-lg text-slate-500 hover:text-slate-300 hover:bg-slate-800 transition-colors"
            >
              <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
            </button>
          </div>
        </div>
      </div>

      <div className="max-w-3xl mx-auto px-6 py-6 space-y-8">

        {/* Pending */}
        <section>
          <h2 className="text-sm font-semibold text-slate-400 uppercase tracking-widest mb-4">
            Pending — {count} waiting
          </h2>
          {count === 0 ? (
            <div className="flex flex-col items-center justify-center py-16 rounded-2xl border border-slate-800 bg-slate-900/30">
              <ShieldCheck size={32} className="text-slate-700 mb-3" />
              <p className="text-slate-500 text-sm">No pending approvals</p>
              <p className="text-slate-700 text-xs mt-1">Auto-refreshes every 5 seconds</p>
            </div>
          ) : (
            <div className="space-y-4">
              {approvals.map(approval => (
                <ApprovalCard
                  key={`${approval.source}-${approval.id}`}
                  approval={approval}
                  onApprove={handleApprove}
                  onReject={handleReject}
                  busy={busy}
                />
              ))}
            </div>
          )}
        </section>

        {/* History */}
        {history.length > 0 && (
          <section>
            <h2 className="text-sm font-semibold text-slate-400 uppercase tracking-widest mb-4">
              History — this session
            </h2>
            <div className="space-y-2">
              {history.map((item, i) => (
                <HistoryCard key={i} item={item} />
              ))}
            </div>
          </section>
        )}

        {/* Info */}
        <section className="rounded-2xl border border-slate-800 bg-slate-900/30 p-5">
          <div className="flex items-start gap-3">
            <AlertTriangle size={15} className="text-amber-400 shrink-0 mt-0.5" />
            <div className="space-y-1">
              <p className="text-xs font-semibold text-slate-300">Human-in-the-loop</p>
              <p className="text-xs text-slate-500 leading-relaxed">
                Sensitive operations (save, delete, disclosing confidential data)
                require your approval before the agent can proceed. The agent is
                paused and waiting — you can watch the tool span grow in Grafana Tempo.
                Approvals time out after 10 minutes; the timer turns amber at 8, red at 9.
              </p>
              <div className="text-xs text-slate-600 mt-2 space-y-0.5">
                <p>Sources polled:</p>
                {APPROVAL_SOURCES.map(s => (
                  <p key={s.id} className="font-mono text-slate-500">· {s.label}</p>
                ))}
              </div>
            </div>
          </div>
        </section>

      </div>
    </div>
  )
}
