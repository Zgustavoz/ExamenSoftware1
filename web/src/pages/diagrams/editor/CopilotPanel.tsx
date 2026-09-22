import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Bot,
  Check,
  CircleAlert,
  CircleCheck,
  Clock,
  CloudOff,
  LoaderCircle,
  Mic,
  MicOff,
  RefreshCw,
  Send,
  User,
  X,
} from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { FormError } from '@/components/FormError'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { confirmAiChanges, listAiChats, type AiMessage, type InputType } from '@/lib/api/copilot'
import { errorMessage } from '@/lib/api/errors'
import type { PendingOp } from '@/lib/offline/pending-store'
import type { OfflineQueue } from '@/lib/offline/queue'
import { offlineQueue, useOfflineQueue } from '@/lib/offline/use-offline-queue'
import { useSpeechRecognition } from '@/lib/use-speech-recognition'
import { cn } from '@/lib/utils'

interface Props {
  diagramId: string
  /** Se llama cuando la IA ya aplicó cambios, para recargar el diagrama del editor. */
  onApplied: () => void
  /** La cola de instrucciones pendientes. Por omisión, la de toda la aplicación; las pruebas pasan la suya. */
  queue?: OfflineQueue
}

/** Cómo se explica en pantalla el estado de una instrucción guardada en el navegador. */
function describe(op: PendingOp): { icon: typeof Clock; text: string } {
  switch (op.status) {
    case 'PENDING':
      return {
        icon: Clock,
        text: `Pendiente de envío${op.attempts > 0 ? ` · intento ${op.attempts}` : ''}${op.error ? `. ${op.error}` : ''}`,
      }
    case 'SYNCING':
      return { icon: LoaderCircle, text: 'Enviando…' }
    case 'DONE':
      return { icon: CircleCheck, text: `Enviada: ${op.result?.explanation ?? ''}` }
    case 'FAILED':
      return { icon: CircleAlert, text: `Rechazada: ${op.error ?? 'el asistente no la aceptó'}` }
  }
}

/** CU-12 Generar o modificar el diagrama con IA, y CU-13 consultar el historial de la conversación. */
export function CopilotPanel({ diagramId, onApplied, queue = offlineQueue }: Props) {
  const queryClient = useQueryClient()
  const { ops, online, syncing } = useOfflineQueue(queue)
  const [notice, setNotice] = useState<string | null>(null)
  const mine = ops.filter((op) => op.diagramId === diagramId).reverse()
  const pendingCount = mine.filter((op) => op.status === 'PENDING' || op.status === 'SYNCING').length

  // Cuando se envían en segundo plano instrucciones de este diagrama, el asistente ya aplicó los cambios (D-07):
  // el editor tiene que releerlo.
  useEffect(
    () =>
      queue.onSynced((done) => {
        if (done.some((op) => op.diagramId === diagramId)) onApplied()
      }),
    [queue, diagramId, onApplied],
  )
  const [instruction, setInstruction] = useState('')
  const [inputType, setInputType] = useState<InputType>('TEXTO')
  const [lastExplanation, setLastExplanation] = useState<string | null>(null)

  const chats = useQuery({ queryKey: ['aiChats', diagramId], queryFn: () => listAiChats(diagramId) })

  const speech = useSpeechRecognition((text) => {
    setInstruction(text)
    setInputType('VOZ')
  })

  const ask = useMutation({
    // Por omisión TanStack Query pausa las mutaciones sin conexión y ni siquiera las ejecuta: la instrucción se
    // quedaría «trabajando» para siempre. Aquí justo hace falta que se ejecute, para guardarla en el navegador.
    networkMode: 'always',
    mutationFn: () => queue.submit({ diagramId, instruction: instruction.trim(), inputType }),
    onSuccess: async (outcome) => {
      setInstruction('')
      setInputType('TEXTO')
      if (outcome.kind === 'queued') {
        // Quedó en el navegador y se enviará sola: por ahora no hay nada que confirmar ni que releer.
        setLastExplanation(null)
        setNotice(
          !online
            ? 'Sin conexión: la instrucción quedó guardada en este navegador y se enviará cuando vuelva la conexión.'
            : outcome.op.error
              ? `No se pudo enviar ahora. ${outcome.op.error} La instrucción quedó guardada y se reintentará.`
              : 'La instrucción quedó en cola detrás de las que estaban pendientes y se envía en orden.',
        )
        return
      }
      setNotice(null)
      setLastExplanation(outcome.result.explanation)
      await queryClient.invalidateQueries({ queryKey: ['aiChats', diagramId] })
      // El asistente aplica los cambios por su cuenta (D-07): el editor tiene que releer el diagrama.
      onApplied()
    },
  })

  const confirm = useMutation({
    mutationFn: () => confirmAiChanges(diagramId),
    onSuccess: () => {
      setLastExplanation(null)
      toast.success('Cambios confirmados.')
      onApplied()
    },
    onError: (error) => toast.error(errorMessage(error)),
  })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!instruction.trim()) return
    ask.mutate()
  }

  const messages: AiMessage[] = chats.data?.flatMap((chat) => chat.messages) ?? []

  return (
    <div className="flex h-full flex-col gap-3">
      {!online && (
        <p className="flex items-start gap-2 rounded-lg bg-amber-50 p-2 text-xs text-amber-900" role="status">
          <CloudOff className="mt-0.5 size-4 shrink-0" aria-hidden />
          Sin conexión. Las instrucciones que escriba se guardarán en este navegador y se enviarán cuando vuelva la
          conexión.
        </p>
      )}

      <div className="min-h-0 flex-1 space-y-3 overflow-y-auto">
        {chats.isPending && (
          <p className="flex items-center gap-2 text-sm text-muted-foreground">
            <LoaderCircle className="size-4 animate-spin" aria-hidden />
            Cargando la conversación…
          </p>
        )}

        {chats.isSuccess && messages.length === 0 && (
          <p className="text-sm text-muted-foreground">
            Pídale al asistente lo que necesite. Por ejemplo: «agrega una clase Cliente con nombre y email».
          </p>
        )}

        <ul className="space-y-3">
          {messages.map((message, index) => (
            <li
              key={`${message.timestamp ?? index}-${index}`}
              className={cn(
                'flex gap-2 rounded-lg p-2 text-sm',
                message.role === 'user' ? 'bg-secondary' : 'bg-accent/40',
              )}
            >
              {message.role === 'user' ? (
                <User className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden />
              ) : (
                <Bot className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden />
              )}
              <p className="whitespace-pre-wrap">{message.content}</p>
            </li>
          ))}
        </ul>

        {ask.isPending && (
          <p className="flex items-center gap-2 text-sm text-muted-foreground">
            <LoaderCircle className="size-4 animate-spin" aria-hidden />
            El asistente está trabajando…
          </p>
        )}

        {/* Un fallo del asistente nunca toca el diagrama (CP-04). */}
        <FormError error={ask.error} />

        {notice && (
          <p className="flex items-start gap-2 rounded-lg border border-primary/30 bg-primary/5 p-2 text-sm">
            <Clock className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden />
            {notice}
          </p>
        )}

        {mine.length > 0 && (
          <section aria-label="Instrucciones guardadas en este navegador" className="space-y-2">
            <header className="flex items-center justify-between gap-2">
              <h3 className="text-sm font-medium">Instrucciones en este navegador</h3>
              {pendingCount > 0 && (
                <Button size="sm" variant="ghost" onClick={() => void queue.sync()} disabled={syncing}>
                  <RefreshCw className={cn('size-4', syncing && 'animate-spin')} aria-hidden />
                  {syncing ? 'Enviando…' : 'Sincronizar ahora'}
                </Button>
              )}
            </header>
            <ul className="space-y-2">
              {mine.map((op) => {
                const { icon: Icon, text } = describe(op)
                return (
                  <li key={op.id} className="flex items-start gap-2 rounded-lg border p-2 text-sm">
                    <Icon
                      className={cn(
                        'mt-0.5 size-4 shrink-0',
                        op.status === 'SYNCING' && 'animate-spin',
                        op.status === 'DONE' && 'text-emerald-600',
                        op.status === 'FAILED' && 'text-destructive',
                      )}
                      aria-hidden
                    />
                    <div className="min-w-0 flex-1">
                      <p className="whitespace-pre-wrap break-words">{op.instruction}</p>
                      <p className="text-xs text-muted-foreground">{text}</p>
                    </div>
                    <Button
                      size="icon"
                      variant="ghost"
                      className="size-6"
                      aria-label="Quitar de la lista"
                      disabled={op.status === 'SYNCING'}
                      onClick={() => void queue.discard(op)}
                    >
                      <X className="size-4" aria-hidden />
                    </Button>
                  </li>
                )
              })}
            </ul>
          </section>
        )}

        {lastExplanation && (
          <div className="rounded-lg border border-primary/30 bg-primary/5 p-3 text-sm">
            <p className="mb-2">{lastExplanation}</p>
            <Button size="sm" onClick={() => confirm.mutate()} disabled={confirm.isPending}>
              {confirm.isPending ? (
                <LoaderCircle className="size-4 animate-spin" aria-hidden />
              ) : (
                <Check className="size-4" aria-hidden />
              )}
              Confirmar cambios
            </Button>
          </div>
        )}
      </div>

      <form onSubmit={submit} className="flex items-center gap-2">
        <Input
          value={instruction}
          onChange={(e) => {
            setInstruction(e.target.value)
            setInputType('TEXTO')
          }}
          placeholder="Escriba una instrucción"
          aria-label="Instrucción para el asistente"
          disabled={ask.isPending}
        />

        {speech.supported && (
          <Button
            type="button"
            size="icon"
            variant={speech.listening ? 'default' : 'outline'}
            aria-label={speech.listening ? 'Dejar de dictar' : 'Dictar por voz'}
            onClick={() => (speech.listening ? speech.stop() : speech.start())}
            disabled={ask.isPending}
          >
            {speech.listening ? <MicOff className="size-4" aria-hidden /> : <Mic className="size-4" aria-hidden />}
          </Button>
        )}

        <Button type="submit" size="icon" aria-label="Enviar instrucción" disabled={ask.isPending || !instruction.trim()}>
          <Send className="size-4" aria-hidden />
        </Button>
      </form>
    </div>
  )
}
