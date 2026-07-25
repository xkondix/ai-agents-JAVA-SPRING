// Approval Flow REST API — now served by SEVERAL modules.
//
//  mcp-server (8081)          → MCP tools: save_note / delete_note.
//                               Called directly (CORS enabled there);
//                               kept untouched as the MCP/STDIO example.
//  patterns-langchain4j (8087) → getSecretRumors 🔒
//  patterns-spring-ai   (8088) → getSecretRumors 🔒
//                               Both reached through the Vite dev proxy,
//                               so no CORS setup is needed on their side.
//
// Every approval carries the id of the source it came from, because
// approve/reject must go back to the very module that is blocking.
export const APPROVAL_SOURCES = [
  { id: 'mcp-server',   label: 'mcp-server · 8081',           base: 'http://localhost:8081/approvals' },
  { id: 'patterns-lc4j',   label: 'patterns-langchain4j · 8087', base: '/api/patterns-lc4j/approvals' },
  { id: 'patterns-spring', label: 'patterns-spring-ai · 8088',   base: '/api/patterns-spring/approvals' },
]

const sourceById = id => APPROVAL_SOURCES.find(s => s.id === id)

/**
 * Fetches pending approvals from ALL sources in parallel.
 * Offline modules are skipped silently — you rarely run everything at once.
 * Each item gets `source` (id) and `sourceLabel` for display.
 */
export async function fetchPendingApprovals() {
  const results = await Promise.all(
    APPROVAL_SOURCES.map(async src => {
      try {
        const res = await fetch(src.base, { signal: AbortSignal.timeout(3000) })
        if (!res.ok) return []
        const data = await res.json()
        return data.map(a => ({ ...a, source: src.id, sourceLabel: src.label }))
      } catch {
        return [] // module not running — ignore
      }
    })
  )
  return results.flat()
}

/** Approve — sent to the module that owns the blocked call. */
export async function approveOperation(id, sourceId) {
  const src = sourceById(sourceId) ?? APPROVAL_SOURCES[0]
  const res = await fetch(`${src.base}/${id}/approve`, { method: 'POST' })
  if (!res.ok) throw new Error(`Approve failed: ${res.status}`)
  return res.text()
}

/** Reject — the waiting tool call returns a refusal to the model. */
export async function rejectOperation(id, sourceId) {
  const src = sourceById(sourceId) ?? APPROVAL_SOURCES[0]
  const res = await fetch(`${src.base}/${id}/reject`, { method: 'POST' })
  if (!res.ok) throw new Error(`Reject failed: ${res.status}`)
  return res.text()
}
