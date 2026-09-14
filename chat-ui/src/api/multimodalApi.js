const BASE = 'http://localhost:8089/api/v1/multimodal'

/**
 * Media URLs come back as paths (/api/v1/multimodal/media/...), not absolute
 * URLs — the backend does not know what host the browser reached it on. The
 * page runs on :3000 and the module on :8089, so every path needs the origin
 * prepended before it can go in an <img> or <audio> tag.
 */
export function mediaUrl(path) {
  if (!path) return ''
  return path.startsWith('http') ? path : `http://localhost:8089${path}`
}

/**
 * One multipart request carries all three inputs. Multipart rather than JSON
 * because a photo and a voice note are binary: base64 in a JSON body inflates
 * them by a third and pushes megabytes through the request logger.
 *
 * The server may spend 30+ seconds here — a chained request runs several
 * model calls in a row (look at the photo, draw something, narrate it) — so
 * there is deliberately no timeout on the fetch.
 */
export async function sendMultimodal({ conversationId, question, image, audio }) {
  const form = new FormData()
  if (question) form.append('question', question)
  if (image) form.append('image', image)
  if (audio) form.append('audio', audio, 'recording.webm')

  const res = await fetch(`${BASE}?conversationId=${encodeURIComponent(conversationId)}`, {
    method: 'POST',
    body: form,
  })
  if (!res.ok) {
    throw new Error(`multimodal-lab returned ${res.status}`)
  }
  return res.json()
}

/**
 * Clears the Redis-backed thread. Generated files stay on disk on purpose:
 * they have URLs and may already be open in another tab.
 */
export async function resetMemory(conversationId) {
  const res = await fetch(`${BASE}/memory/${encodeURIComponent(conversationId)}`, {
    method: 'DELETE',
  })
  if (!res.ok) {
    throw new Error(`reset failed with ${res.status}`)
  }
}

export async function isUp() {
  try {
    const res = await fetch('http://localhost:8089/actuator/health')
    return res.ok
  } catch {
    return false
  }
}
