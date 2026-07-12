// Approval Flow REST API — served by mcp-server (HTTP) on port 8081.
//
// Note: code-mcp-server (STDIO, port 8086) cannot host this reliably because
// of the STDIO+HTTP conflict, so the Approval Flow demo runs against the
// HTTP-based mcp-server instead.
const BASE = 'http://localhost:8081/approvals'

/**
 * Fetch all pending approval requests.
 */
export async function fetchPendingApprovals() {
  const res = await fetch(BASE)
  if (!res.ok) throw new Error(`Fetch approvals failed: ${res.status}`)
  return res.json()
}

/**
 * Approve a pending operation.
 * Unblocks the MCP server tool call and allows execution.
 */
export async function approveOperation(id) {
  const res = await fetch(`${BASE}/${id}/approve`, { method: 'POST' })
  if (!res.ok) throw new Error(`Approve failed: ${res.status}`)
  return res.text()
}

/**
 * Reject a pending operation.
 * Cancels the tool call — agent receives "REJECTED" as result.
 */
export async function rejectOperation(id) {
  const res = await fetch(`${BASE}/${id}/reject`, { method: 'POST' })
  if (!res.ok) throw new Error(`Reject failed: ${res.status}`)
  return res.text()
}
