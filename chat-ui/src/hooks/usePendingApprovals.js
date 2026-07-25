import { useState, useEffect, useCallback } from 'react'
import { fetchPendingApprovals } from '../api/approvalsApi.js'

const INTERVAL = 5_000 // 5 seconds

/**
 * Polls EVERY approval source (mcp-server + both patterns modules) every
 * 5 seconds and merges the pending requests into one list.
 * Each item carries `source` / `sourceLabel` so the UI knows where to send
 * the decision.
 *
 * Returns { approvals, count, refresh, loading }
 */
export function usePendingApprovals() {
  const [approvals, setApprovals] = useState([])
  const [loading, setLoading]     = useState(false)

  const fetchApprovals = useCallback(async () => {
    try {
      const all = await fetchPendingApprovals()
      setApprovals(all.filter(a => a.status === 'PENDING'))
    } catch {
      // all sources offline — silently ignore
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
