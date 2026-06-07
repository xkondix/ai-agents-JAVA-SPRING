/**
 * Wysyla wiadomosc do wybranego agenta.
 * Obsluguje:
 *   - zwykly JSON response
 *   - streaming (text/event-stream SSE)
 *
 * @param {object} agent    - obiekt z AGENTS
 * @param {string} message  - wiadomosc uzytkownika
 * @param {string} userId   - id sesji / uzytkownika
 * @param {function} onChunk - callback dla streaming (opcjonalny)
 * @returns {Promise<string>} - pelna odpowiedz
 */
export async function sendMessage(agent, message, userId, onChunk) {
  const body = {
    [agent.chatField]: message,
    userId,
    conversationId: userId,
  }

  const res = await fetch(agent.chatPath, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify(body),
  })

  if (!res.ok) {
    const err = await res.text()
    throw new Error(`Agent error ${res.status}: ${err}`)
  }

  const contentType = res.headers.get('content-type') || ''

  // Streaming SSE
  if (contentType.includes('text/event-stream') && onChunk) {
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let full = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      const chunk = decoder.decode(value, { stream: true })
      // parsuj SSE: "data: {...}"
      for (const line of chunk.split('\n')) {
        if (line.startsWith('data:')) {
          try {
            const json = JSON.parse(line.slice(5).trim())
            const text = json.content ?? json.text ?? json
            full += text
            onChunk(full)
          } catch {
            // plain text chunk
            full += line.slice(5).trim()
            onChunk(full)
          }
        }
      }
    }
    return full
  }

  // Common  JSON
  const json = await res.json()
  return json.content ?? json.message ?? json.response ?? JSON.stringify(json)
}

/**
 * Sends a file/image to the agent (multipart)
 */
export async function sendFile(agent, file, message, userId) {
  const form = new FormData()
  form.append('file', file)
  form.append('message', message)
  form.append('userId', userId)

  const res = await fetch(agent.chatPath + '/file', {
    method: 'POST',
    body:   form,
  })
  if (!res.ok) throw new Error(`File upload error ${res.status}`)
  const json = await res.json()
  return json.content ?? json.message ?? JSON.stringify(json)
}
