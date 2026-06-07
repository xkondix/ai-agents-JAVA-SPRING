import { useState, useEffect, useCallback } from 'react'
import { AGENTS } from '../agents.js'

/**
 * Polls every agent for health every INTERVAL ms.
 * Returns a map { agentId -> 'up' | 'down' | 'checking' }
 */
const INTERVAL = 15_000 // 15 sekund

export function useAgentHealth() {
  const [status, setStatus] = useState(() =>
    Object.fromEntries(AGENTS.map(a => [a.id, 'checking']))
  )

  const checkAll = useCallback(async () => {
    const results = await Promise.allSettled(
      AGENTS.map(async agent => {
        try {
          const res = await fetch(agent.healthPath, {
            signal: AbortSignal.timeout(3000)
          })
          return { id: agent.id, status: res.ok ? 'up' : 'down' }
        } catch {
          return { id: agent.id, status: 'down' }
        }
      })
    )
    setStatus(prev => {
      const next = { ...prev }
      results.forEach(r => {
        if (r.status === 'fulfilled') next[r.value.id] = r.value.status
      })
      return next
    })
  }, [])

  useEffect(() => {
    checkAll()
    const timer = setInterval(checkAll, INTERVAL)
    return () => clearInterval(timer)
  }, [checkAll])

  return { status, refresh: checkAll }
}
