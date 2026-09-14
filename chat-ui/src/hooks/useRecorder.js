import { useEffect, useRef, useState } from 'react'

/**
 * Microphone capture via MediaRecorder.
 *
 * WHY WEBM AND NOT MP3. The browser decides the container, not us —
 * MediaRecorder produces webm/opus in Chrome and Firefox, mp4 in Safari.
 * Whisper accepts both, but only if the FILENAME carries the extension,
 * which is why the backend rebuilds a named Resource before sending. Trying
 * to force a format here would just fail on one browser or the other.
 *
 * The stream tracks are stopped explicitly on every path. Without that the
 * browser keeps the tab's recording indicator lit after the first take, which
 * on a stage looks exactly like a bug.
 */
export function useRecorder() {
  const [recording, setRecording] = useState(false)
  const [seconds, setSeconds] = useState(0)
  const [error, setError] = useState(null)

  const recorderRef = useRef(null)
  const chunksRef = useRef([])
  const streamRef = useRef(null)
  const timerRef = useRef(null)
  const resolveRef = useRef(null)

  useEffect(() => () => cleanup(), [])

  function cleanup() {
    clearInterval(timerRef.current)
    streamRef.current?.getTracks().forEach(t => t.stop())
    streamRef.current = null
    recorderRef.current = null
  }

  async function start() {
    setError(null)
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      streamRef.current = stream
      chunksRef.current = []

      const recorder = new MediaRecorder(stream)
      recorderRef.current = recorder

      recorder.ondataavailable = e => {
        if (e.data.size > 0) chunksRef.current.push(e.data)
      }
      recorder.onstop = () => {
        const blob = new Blob(chunksRef.current, { type: recorder.mimeType })
        cleanup()
        setRecording(false)
        setSeconds(0)
        resolveRef.current?.(blob)
        resolveRef.current = null
      }

      recorder.start()
      setRecording(true)
      setSeconds(0)
      timerRef.current = setInterval(() => setSeconds(s => s + 1), 1000)

    } catch (e) {
      // Denied permission, no device, or an insecure origin. All three look
      // the same to the user, so say what to do rather than what broke.
      setError('Microphone unavailable — check the browser permission.')
      setRecording(false)
    }
  }

  /** Resolves with the recorded Blob once the recorder has flushed. */
  function stop() {
    return new Promise(resolve => {
      if (!recorderRef.current || recorderRef.current.state === 'inactive') {
        resolve(null)
        return
      }
      resolveRef.current = resolve
      recorderRef.current.stop()
    })
  }

  function cancel() {
    if (recorderRef.current && recorderRef.current.state !== 'inactive') {
      resolveRef.current = () => {}
      recorderRef.current.stop()
    }
    cleanup()
    setRecording(false)
    setSeconds(0)
  }

  return { recording, seconds, error, start, stop, cancel }
}
