import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AiResult } from '@/lib/api/copilot'
import { ApiError, NETWORK_ERROR_MESSAGE } from '@/lib/api/errors'
import { MemoryPendingStore } from './pending-store'
import { OfflineQueue, type Connectivity } from './queue'

/** Conectividad de prueba: se enciende y se apaga a voluntad. */
class FakeConnectivity implements Connectivity {
  online: boolean
  private listeners = new Set<(online: boolean) => void>()
  constructor(online = true) {
    this.online = online
  }
  isOnline = () => this.online
  subscribe = (listener: (online: boolean) => void) => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }
  set(online: boolean) {
    this.online = online
    this.listeners.forEach((l) => l(online))
  }
}

const OWNER = 'c1/u1'
const sinRed = () => new ApiError('NETWORK_ERROR', NETWORK_ERROR_MESSAGE)

function resultFor(instruction: string): AiResult {
  return { explanation: `Hecho: ${instruction}`, operations: [], diagram: {} as AiResult['diagram'] }
}

describe('OfflineQueue (modo offline del Copilot)', () => {
  let store: MemoryPendingStore
  let net: FakeConnectivity
  let enviadas: string[]
  /** Si devuelve un error para una instrucción, se lanza en vez de ejecutarla. */
  let fallo: (instruction: string) => Error | null
  /** Mientras haya una puerta, el envío espera a que se abra: permite ver una instrucción «en vuelo». */
  let puerta: Promise<void> | null
  let queue: OfflineQueue

  async function crear(online = true, opts: { retryEveryMs?: number | null } = {}) {
    net = new FakeConnectivity(online)
    queue = new OfflineQueue({
      store,
      connectivity: net,
      retryEveryMs: opts.retryEveryMs === undefined ? null : opts.retryEveryMs,
      send: async ({ instruction }) => {
        await puerta
        const error = fallo(instruction)
        if (error) throw error
        enviadas.push(instruction)
        return resultFor(instruction)
      },
    })
    queue.start()
    await queue.setOwner(OWNER)
    await settle()
  }

  const dictar = (instruction: string, diagramId = 'd1') => queue.submit({ diagramId, instruction, inputType: 'TEXTO' })

  /** Deja terminar los envíos en segundo plano. */
  async function settle() {
    for (let i = 0; i < 20; i++) await Promise.resolve()
    await vi.waitFor(() => expect(queue.getSnapshot().syncing).toBe(false))
  }

  beforeEach(() => {
    store = new MemoryPendingStore()
    enviadas = []
    fallo = () => null
    puerta = null
  })
  afterEach(() => queue.stop())

  describe('con conexión', () => {
    it('envía la instrucción al momento y no guarda nada', async () => {
      await crear()

      const r = await dictar('agrega una clase Cliente')

      expect(r.kind).toBe('sent')
      expect(enviadas).toEqual(['agrega una clase Cliente'])
      expect(queue.getSnapshot().ops).toEqual([])
    })

    it('un fallo de red al enviar la deja guardada en vez de perderla', async () => {
      fallo = () => sinRed()
      await crear()

      const r = await dictar('agrega una clase Cliente')

      expect(r.kind).toBe('queued')
      const [op] = queue.getSnapshot().ops
      expect(op.status).toBe('PENDING')
      expect(op.attempts).toBe(1)
      expect(op.error).toBe(NETWORK_ERROR_MESSAGE)
    })

    it('un fallo del asistente se le muestra al usuario y NO se guarda (el diagrama queda intacto, CP-04)', async () => {
      fallo = () => new ApiError('AI_UNAVAILABLE', 'El asistente no está disponible.')
      await crear()

      await expect(dictar('agrega una clase Cliente')).rejects.toMatchObject({ code: 'AI_UNAVAILABLE' })

      expect(queue.getSnapshot().ops).toEqual([])
    })

    it('un rechazo del servidor tampoco se guarda: reintentar daría lo mismo', async () => {
      fallo = () => new ApiError('VALIDATION_ERROR', 'Escriba una instrucción.')
      await crear()

      await expect(dictar('   ')).rejects.toMatchObject({ code: 'VALIDATION_ERROR' })

      expect(queue.getSnapshot().ops).toEqual([])
    })
  })

  describe('sin conexión', () => {
    it('guarda la instrucción en el navegador sin llamar al servidor', async () => {
      await crear(false)

      const r = await dictar('agrega una clase Cliente')

      expect(r.kind).toBe('queued')
      expect(enviadas).toEqual([])
      const guardadas = await store.all(OWNER)
      expect(guardadas).toHaveLength(1)
      expect(guardadas[0]).toMatchObject({ instruction: 'agrega una clase Cliente', status: 'PENDING', diagramId: 'd1' })
    })

    it('al volver la conexión envía lo pendiente en el orden en que se escribió', async () => {
      await crear(false)
      const avisadas: number[] = []
      queue.onSynced((done) => avisadas.push(done.length))
      await dictar('primera')
      await dictar('segunda')
      await dictar('tercera')
      expect(enviadas).toEqual([])

      net.set(true)
      await settle()

      expect(enviadas).toEqual(['primera', 'segunda', 'tercera'])
      expect(queue.getSnapshot().ops.map((o) => o.status)).toEqual(['DONE', 'DONE', 'DONE'])
      expect(queue.pendingCount).toBe(0)
      expect(avisadas).toEqual([3])
    })

    it('lo enviado guarda la respuesta del asistente para enseñarla', async () => {
      await crear(false)
      await dictar('agrega una clase Cliente')

      net.set(true)
      await settle()

      expect(queue.getSnapshot().ops[0].result).toEqual({ explanation: 'Hecho: agrega una clase Cliente' })
    })

    it('si la red se cae de nuevo, lo que queda sigue pendiente y en orden', async () => {
      await crear(false)
      await dictar('A')
      await dictar('B')
      await dictar('C')
      fallo = (i) => (i === 'B' ? sinRed() : null)

      net.set(true)
      await settle()

      // A salió; B falló y C ni se intentó: enviarla antes rompería el orden.
      expect(enviadas).toEqual(['A'])
      expect(queue.getSnapshot().ops.map((o) => o.status)).toEqual(['DONE', 'PENDING', 'PENDING'])
      expect(queue.getSnapshot().ops.map((o) => o.attempts)).toEqual([1, 1, 0])

      fallo = () => null
      await queue.sync()

      expect(enviadas).toEqual(['A', 'B', 'C'])
      expect(queue.pendingCount).toBe(0)
    })

    it('una instrucción rechazada queda como fallida y las siguientes se envían igualmente', async () => {
      await crear(false)
      await dictar('buena 1')
      await dictar('mala')
      await dictar('buena 2')
      fallo = (i) => (i === 'mala' ? new ApiError('VALIDATION_ERROR', 'No entendí la instrucción.') : null)

      net.set(true)
      await settle()

      expect(enviadas).toEqual(['buena 1', 'buena 2'])
      expect(queue.getSnapshot().ops.map((o) => o.status)).toEqual(['DONE', 'FAILED', 'DONE'])
      expect(queue.getSnapshot().ops[1].error).toBe('No entendí la instrucción.')
    })

    it('si el asistente no responde, se reintenta unas veces y luego queda como fallida', async () => {
      await crear(false)
      await dictar('agrega una clase Cliente')
      fallo = () => new ApiError('AI_UNAVAILABLE', 'El asistente no está disponible.')

      net.set(true)
      await settle()
      expect(queue.getSnapshot().ops[0]).toMatchObject({ status: 'PENDING', attempts: 1 })

      await queue.sync()
      await queue.sync()

      // No se reintenta para siempre contra un asistente que no contesta.
      expect(queue.getSnapshot().ops[0]).toMatchObject({ status: 'FAILED', attempts: 3 })
    })

    it('con la sesión vencida las instrucciones siguen guardadas y no cuentan como intento', async () => {
      await crear(false)
      await dictar('agrega una clase Cliente')
      fallo = () => new ApiError('UNAUTHORIZED', 'La sesión venció.')

      net.set(true)
      await settle()

      expect(queue.getSnapshot().ops[0]).toMatchObject({ status: 'PENDING', attempts: 0 })
      expect(enviadas).toEqual([])
    })
  })

  describe('orden y concurrencia', () => {
    it('una instrucción nueva no adelanta a la que se está enviando', async () => {
      await crear(false)
      await dictar('A')
      // A queda «en vuelo»: el servidor todavía no contesta.
      let abrir: () => void = () => {}
      puerta = new Promise<void>((r) => (abrir = r))

      net.set(true)
      await vi.waitFor(() => expect(queue.getSnapshot().ops[0]?.status).toBe('SYNCING'))

      const r = await dictar('B')
      expect(r.kind).toBe('queued')
      expect(enviadas).toEqual([])

      abrir()
      await vi.waitFor(() => expect(enviadas).toEqual(['A', 'B']))
    })

    it('dos sincronizaciones a la vez no envían la misma instrucción dos veces', async () => {
      await crear(false)
      await dictar('agrega una clase Cliente')

      await Promise.all([queue.sync(), queue.sync()])

      expect(enviadas).toEqual(['agrega una clase Cliente'])
    })

    it('las instrucciones de otro usuario no se ven ni se envían con esta sesión', async () => {
      await store.add({
        owner: 'c1/otro',
        diagramId: 'd1',
        instruction: 'ajena',
        inputType: 'TEXTO',
        status: 'PENDING',
        attempts: 0,
        error: null,
        result: null,
        createdAt: new Date().toISOString(),
      })
      await crear()

      await queue.sync()

      expect(enviadas).toEqual([])
      expect(queue.getSnapshot().ops).toEqual([])
      expect(await store.all('c1/otro')).toHaveLength(1)
    })

    it('sin sesión asociada envía directamente y no guarda nada', async () => {
      net = new FakeConnectivity(true)
      queue = new OfflineQueue({ store, connectivity: net, retryEveryMs: null, send: async ({ instruction }) => resultFor(instruction) })

      const r = await queue.submit({ diagramId: 'd1', instruction: 'x', inputType: 'TEXTO' })

      expect(r.kind).toBe('sent')
      expect(await store.all(OWNER)).toEqual([])
    })
  })

  describe('recuperación', () => {
    it('una instrucción que se quedó «enviándose» al cerrar la pestaña vuelve a pendiente y se envía', async () => {
      await store.add({
        owner: OWNER,
        diagramId: 'd1',
        instruction: 'interrumpida',
        inputType: 'TEXTO',
        status: 'SYNCING',
        attempts: 1,
        error: null,
        result: null,
        createdAt: new Date().toISOString(),
      })

      await crear()
      await queue.sync()

      expect(enviadas).toEqual(['interrumpida'])
      expect(queue.getSnapshot().ops[0].status).toBe('DONE')
    })

    it('las guardadas de una sesión anterior se envían al volver a entrar', async () => {
      await store.add({
        owner: OWNER,
        diagramId: 'd1',
        instruction: 'de ayer',
        inputType: 'VOZ',
        status: 'PENDING',
        attempts: 0,
        error: null,
        result: null,
        createdAt: new Date().toISOString(),
      })

      await crear()
      await vi.waitFor(() => expect(enviadas).toEqual(['de ayer']))
    })

    it('quitar una instrucción pendiente la borra del navegador', async () => {
      await crear(false)
      await dictar('no la quiero')

      await queue.discard(queue.getSnapshot().ops[0])

      expect(queue.getSnapshot().ops).toEqual([])
      expect(await store.all(OWNER)).toEqual([])
    })

    it('el historial de enviadas no crece sin límite', async () => {
      await crear()
      for (let i = 0; i < 25; i++) {
        await store.add({
          owner: OWNER,
          diagramId: 'd1',
          instruction: `vieja ${i}`,
          inputType: 'TEXTO',
          status: 'DONE',
          attempts: 1,
          error: null,
          result: { explanation: 'ok' },
          createdAt: new Date().toISOString(),
        })
      }
      await store.add({
        owner: OWNER,
        diagramId: 'd1',
        instruction: 'nueva',
        inputType: 'TEXTO',
        status: 'PENDING',
        attempts: 0,
        error: null,
        result: null,
        createdAt: new Date().toISOString(),
      })

      await queue.sync()

      const quedan = await store.all(OWNER)
      expect(quedan.filter((o) => o.status === 'DONE')).toHaveLength(20)
      expect(quedan.some((o) => o.instruction === 'vieja 0')).toBe(false)
    })
  })

  it('el reintento automático envía lo pendiente cuando el asistente vuelve, sin cambio de red', async () => {
    vi.useFakeTimers()
    try {
      fallo = () => sinRed()
      net = new FakeConnectivity(true)
      queue = new OfflineQueue({
        store,
        connectivity: net,
        retryEveryMs: 20_000,
        send: async ({ instruction }) => {
          const error = fallo(instruction)
          if (error) throw error
          enviadas.push(instruction)
          return resultFor(instruction)
        },
      })
      queue.start()
      await queue.setOwner(OWNER)
      await dictar('agrega una clase Cliente')
      expect(queue.pendingCount).toBe(1)

      // La red nunca se cayó, así que ningún evento de conexión lo avisa: solo el reintento periódico.
      fallo = () => null
      await vi.advanceTimersByTimeAsync(21_000)

      expect(enviadas).toEqual(['agrega una clase Cliente'])
      expect(queue.pendingCount).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })
})
