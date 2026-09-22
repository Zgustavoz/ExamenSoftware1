import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Reconocimiento de voz del navegador (Web Speech API). La transcripción ocurre **en el cliente** (D-06):
 * al backend solo llega texto, marcado con `inputType = VOZ`.
 */
interface SpeechRecognitionLike {
  lang: string
  continuous: boolean
  interimResults: boolean
  start: () => void
  stop: () => void
  onresult: ((event: { results: ArrayLike<ArrayLike<{ transcript: string }>> }) => void) | null
  onerror: (() => void) | null
  onend: (() => void) | null
}

type SpeechRecognitionConstructor = new () => SpeechRecognitionLike

function speechRecognitionClass(): SpeechRecognitionConstructor | null {
  const w = window as unknown as {
    SpeechRecognition?: SpeechRecognitionConstructor
    webkitSpeechRecognition?: SpeechRecognitionConstructor
  }
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null
}

export function useSpeechRecognition(onTranscript: (text: string) => void) {
  const [listening, setListening] = useState(false)
  const recognition = useRef<SpeechRecognitionLike | null>(null)
  const callback = useRef(onTranscript)

  const supported = speechRecognitionClass() !== null

  // La referencia guarda siempre la última función, para que `start` no dependa de ella y no se recree.
  useEffect(() => {
    callback.current = onTranscript
  }, [onTranscript])

  useEffect(() => {
    return () => recognition.current?.stop()
  }, [])

  const start = useCallback(() => {
    const Recognition = speechRecognitionClass()
    if (!Recognition) return

    const instance = new Recognition()
    instance.lang = 'es-ES'
    instance.continuous = false
    instance.interimResults = false
    instance.onresult = (event) => {
      const text = Array.from({ length: event.results.length }, (_, i) => event.results[i][0].transcript).join(' ')
      callback.current(text.trim())
    }
    instance.onerror = () => setListening(false)
    instance.onend = () => setListening(false)

    recognition.current = instance
    instance.start()
    setListening(true)
  }, [])

  const stop = useCallback(() => {
    recognition.current?.stop()
    setListening(false)
  }, [])

  return { supported, listening, start, stop }
}
