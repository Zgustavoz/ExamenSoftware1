import type { AiResult, InputType } from '@/lib/api/copilot'
import { ApiError } from '@/lib/api/errors'
import type { PendingOp, PendingStore } from './pending-store'

/** Si el navegador tiene red. Es una pista, no una garantía: una wifi sin salida a internet cuenta como conectada. */
export interface Connectivity {
  isOnline(): boolean
  /** Avisa cada vez que se gana o se pierde la red. Devuelve la función para dejar de escuchar. */
  subscribe(listener: (online: boolean) => void): () => void
}

/** La implementación real, con los eventos `online` / `offline` del navegador. */
export const browserConnectivity: Connectivity = {
  isOnline: () => (typeof navigator === 'undefined' ? true : navigator.onLine),
  subscribe(listener) {
    const on = () => listener(true)
    const off = () => listener(false)
    window.addEventListener('online', on)
    window.addEventListener('offline', off)
    return () => {
      window.removeEventListener('online', on)
      window.removeEventListener('offline', off)
    }
  },
}

/**
 * Deja pasar a **una sola pestaña** a la vez. Con dos pestañas abiertas, las dos verían las mismas instrucciones
 * pendientes y las enviarían por duplicado. Sin la API de cerrojos del navegador, se ejecuta sin más.
 */
export function browserLock<T>(work: () => Promise<T>): Promise<T | undefined> {
  const locks = typeof navigator === 'undefined' ? undefined : navigator.locks
  if (!locks) return work()
  return locks.request('diagramas-offline-sync', { ifAvailable: true }, async (lock) => (lock ? work() : undefined))
}

export interface QueueInput {
  diagramId: string
  instruction: string
  inputType: InputType
}

/** Lo que pasó con una instrucción al pedir que se enviara: se ejecutó ya, o quedó guardada para más tarde. */
export type SubmitOutcome = { kind: 'sent'; result: AiResult } | { kind: 'queued'; op: PendingOp }

export interface QueueState {
  ops: PendingOp[]
  online: boolean
  syncing: boolean
}

export interface QueueDeps {
  store: PendingStore
  send: (input: QueueInput) => Promise<AiResult>
  connectivity: Connectivity
  /** Cada cuánto reintenta mientras haya pendientes. `null` lo desactiva (pruebas). */
  retryEveryMs?: number | null
  lock?: <T>(work: () => Promise<T>) => Promise<T | undefined>
}

/** Errores que se arreglan solos con el tiempo: la instrucción se conserva y se reintenta. */
const RETRYABLE = new Set(['NETWORK_ERROR', 'AI_UNAVAILABLE', 'AI_TIMEOUT'])
/** Si es el asistente el que falla, no se reintenta para siempre: tras estos intentos queda como rechazada. */
const MAX_ASSISTANT_ATTEMPTS = 3

/**
 * Modo offline del Copilot (CU-18 en la web; el enunciado lo deja opcional, D-11).
 *
 * - Sin conexión, o si el envío falla por la red, la instrucción se guarda en IndexedDB.
 * - Al volver la conexión —o al reintentar cada cierto tiempo, o a mano— se envían **en el orden en que se
 *   escribieron**, de una en una.
 * - Un fallo de red o del asistente detiene el envío y deja lo que queda pendiente. Un rechazo del servidor
 *   marca esa instrucción como fallida y sigue con la siguiente, porque reintentarla daría lo mismo.
 *
 * Es la versión web de lo que el móvil hace con SQLite (D-36).
 */
export class OfflineQueue {
  private state: QueueState
  private owner: string | null = null
  private readonly listeners = new Set<() => void>()
  private readonly syncedListeners = new Set<(done: PendingOp[]) => void>()
  private unsubscribe: (() => void) | null = null
  private timer: ReturnType<typeof setInterval> | null = null

  private readonly deps: QueueDeps

  constructor(deps: QueueDeps) {
    this.deps = deps
    this.state = { ops: [], online: deps.connectivity.isOnline(), syncing: false }
  }

  // ---- lectura (para useSyncExternalStore) ----

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  getSnapshot = (): QueueState => this.state

  /** Se avisa con las instrucciones que se acaban de enviar en segundo plano, para refrescar lo que dependa de ellas. */
  onSynced(listener: (done: PendingOp[]) => void): () => void {
    this.syncedListeners.add(listener)
    return () => this.syncedListeners.delete(listener)
  }

  get pendingCount(): number {
    return this.state.ops.filter((o) => o.status === 'PENDING' || o.status === 'SYNCING').length
  }

  // ---- ciclo de vida ----

  /** Empieza a escuchar la red y a reintentar. Se puede llamar más de una vez. */
  start(): void {
    if (this.unsubscribe) return
    this.patch({ online: this.deps.connectivity.isOnline() })
    this.unsubscribe = this.deps.connectivity.subscribe((online) => {
      this.patch({ online })
      if (online) void this.sync()
    })
    const every = this.deps.retryEveryMs === undefined ? 20_000 : this.deps.retryEveryMs
    if (every !== null) {
      this.timer = setInterval(() => {
        if (this.state.online && this.pendingCount > 0) void this.sync()
      }, every)
    }
  }

  stop(): void {
    this.unsubscribe?.()
    this.unsubscribe = null
    if (this.timer) clearInterval(this.timer)
    this.timer = null
  }

  /**
   * Quién está usando el navegador. Las instrucciones guardadas son de cada usuario: al cambiar de usuario solo se
   * ven y se envían las suyas. Con `null` (sin sesión) no se envía nada, pero lo guardado se conserva.
   */
  async setOwner(owner: string | null): Promise<void> {
    if (owner === this.owner) return
    this.owner = owner
    await this.reload()
    if (owner && this.state.online) void this.sync()
  }

  // ---- acciones ----

  /**
   * Envía la instrucción ya, o la guarda si no se puede. Un rechazo del servidor se lanza como `ApiError`: esa
   * instrucción no se guarda, porque hay que corregirla, no esperar.
   */
  async submit(input: QueueInput): Promise<SubmitOutcome> {
    const owner = this.owner
    // Sin sesión asociada no hay a quién guardarle nada: se envía directamente.
    if (!owner) return { kind: 'sent', result: await this.deps.send(input) }

    // Con instrucciones anteriores esperando, la nueva va detrás: si se enviara primero, se ejecutaría en otro
    // orden del que el usuario las escribió.
    const hayCola = this.state.ops.some((o) => o.status === 'PENDING' || o.status === 'SYNCING')
    if (this.state.online && !hayCola) {
      try {
        return { kind: 'sent', result: await this.deps.send(input) }
      } catch (error) {
        if (!(error instanceof ApiError) || error.code !== 'NETWORK_ERROR') throw error
        return { kind: 'queued', op: await this.enqueue(owner, input, 1, error.message) }
      }
    }

    const op = await this.enqueue(owner, input, 0, null)
    if (this.state.online) void this.sync()
    return { kind: 'queued', op }
  }

  /** Envía las pendientes, en orden. Devuelve cuántas se ejecutaron. */
  async sync(): Promise<number> {
    const owner = this.owner
    if (!owner || this.state.syncing) return 0

    this.patch({ syncing: true })
    const done: PendingOp[] = []
    try {
      const lock = this.deps.lock ?? ((work) => work())
      await lock(async () => {
        await this.deps.store.recoverInterrupted()
        // Se vuelve a leer la lista por si mientras tanto se escribió otra instrucción.
        outer: while (this.owner === owner) {
          const pendientes = (await this.deps.store.all(owner)).filter((o) => o.status === 'PENDING')
          if (pendientes.length === 0) break

          for (const op of pendientes) {
            if (this.owner !== owner) break outer
            op.status = 'SYNCING'
            op.attempts += 1
            await this.deps.store.update(op)
            await this.reload()

            try {
              const result = await this.deps.send({ diagramId: op.diagramId, instruction: op.instruction, inputType: op.inputType })
              op.status = 'DONE'
              op.error = null
              op.result = { explanation: result.explanation }
              await this.deps.store.update(op)
              done.push(op)
            } catch (error) {
              const code = error instanceof ApiError ? error.code : 'UNKNOWN'
              op.error = error instanceof Error ? error.message : 'No se pudo enviar.'
              if (code === 'UNAUTHORIZED') {
                // La sesión venció y se cerró sola: no fue un intento de verdad. Las instrucciones siguen
                // guardadas y se envían al volver a entrar.
                op.status = 'PENDING'
                op.attempts -= 1
                await this.deps.store.update(op)
                break outer
              }
              if (RETRYABLE.has(code) && (code === 'NETWORK_ERROR' || op.attempts < MAX_ASSISTANT_ATTEMPTS)) {
                // Sin red o sin asistente: lo que queda detrás también fallaría, y hay que respetar el orden.
                op.status = 'PENDING'
                await this.deps.store.update(op)
                break outer
              }
              op.status = 'FAILED'
              await this.deps.store.update(op)
            }
          }
        }
        await this.deps.store.pruneDone(owner)
      })
    } finally {
      this.patch({ syncing: false })
      await this.reload()
    }

    if (done.length > 0) this.syncedListeners.forEach((l) => l(done))
    return done.length
  }

  /** Quita una instrucción de la lista del navegador (pendiente, rechazada o ya enviada). */
  async discard(op: PendingOp): Promise<void> {
    if (op.id === undefined || op.status === 'SYNCING') return
    await this.deps.store.delete(op.id)
    await this.reload()
  }

  // ---- internos ----

  private async enqueue(owner: string, input: QueueInput, attempts: number, error: string | null): Promise<PendingOp> {
    const op = await this.deps.store.add({
      owner,
      diagramId: input.diagramId,
      instruction: input.instruction,
      inputType: input.inputType,
      status: 'PENDING',
      attempts,
      error,
      result: null,
      createdAt: new Date().toISOString(),
    })
    await this.reload()
    return op
  }

  private async reload(): Promise<void> {
    const owner = this.owner
    this.patch({ ops: owner ? await this.deps.store.all(owner) : [] })
  }

  private patch(partial: Partial<QueueState>): void {
    this.state = { ...this.state, ...partial }
    this.listeners.forEach((l) => l())
  }
}
