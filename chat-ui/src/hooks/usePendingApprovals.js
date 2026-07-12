import { useState, useEffect, useCallback } from 'react'

// Approval Flow is served by mcp-server (HTTP) on port 8081.
const APPROVALS_URL = 'http://localhost:8081/approvals'
const INTERVAL = 5_000 // 5 seconds

/**
 * Polls mcp-server for pending approvals every 5 seconds.
 * Returns { approvals, count, refresh, loading }
 */
export function usePendingApprovals() {
  const [approvals, setApprovals] = useState([])
  const [loading, setLoading]     = useState(false)

  const fetchApprovals = useCallback(async () => {
    try {
      const res = await fetch(APPROVALS_URL, {
        signal: AbortSignal.timeout(3000)
      })
      if (res.ok) {
        const data = await res.json()
        setApprovals(data.filter(a => a.status === 'PENDING'))
      }
    } catch {
      // server might be offline — silently ignore
    }
  }, [])

  const refresh = useCallback(async () => {
    setLoading(true)
    await fetchApprovals()
    setLoading(false)
  }, [fetchApprovals])

  useEffect(() => {
    fetchApprovals()
    const timer = setInterval(fetchApprovals, INTERVAL)
    return () => clearInterval(timer)
  }, [fetchApprovals])

  return { approvals, count: approvals.length, refresh, loading }
}
