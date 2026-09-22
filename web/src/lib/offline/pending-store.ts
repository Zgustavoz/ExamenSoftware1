import type { InputType } from '@/lib/api/copilot'

/**
 * Estado de una instrucción guardada en el navegador (sección 11.2: `pending_ops.status`). No hay `CONFLICT`:
 * una instrucción al asistente es un comando, no un guardado con `baseVersion`, y no puede chocar con la versión
 * de nadie.
 */
export type PendingStatus = 'PENDING' | 'SYNCING' | 'DONE' | 'FAILED'

/** Una instrucción al asistente dictada o escrita y guardada hasta que se pueda enviar. */
export interface PendingOp {
  /** `undefined` hasta que el almacén la guarda. */
  id?: number
  /** `empresa/usuario`: una instrucción solo se envía con la sesión de quien la escribió. */
  owner: string
  diagramId: string
  instruction: string
  inputType: InputType
  status: PendingStatus
  attempts: number
  /** Por qué no se pudo enviar todavía, o por qué se rechazó. */
  error: string | null
  /** Lo que respondió el asistente cuando la instrucción se ejecutó. */
  result: { explanation: string } | null
  createdAt: string
}

/** Dónde se guardan las instrucciones pendientes. En el navegador es IndexedDB; en las pruebas, memoria. */
export interface PendingStore {
  /** Guarda una instrucción nueva y la devuelve con su `id`. */
  add(op: Omit<PendingOp, 'id'>): Promise<PendingOp>
  update(op: PendingOp): Promise<void>
  delete(id: number): Promise<void>
  /** Todas las de un usuario, de la más antigua a la más nueva. */
  all(owner: string): Promise<PendingOp[]>
  /**
   * Una instrucción que se quedó `SYNCING` porque la pestaña se cerró a mitad del envío no se está enviando:
   * vuelve a `PENDING`. Solo se llama con el cerrojo de sincronización tomado, así que nadie más está enviando.
   */
  recoverInterrupted(): Promise<void>
  /** Conserva solo las últimas `keep` instrucciones ya enviadas, para que el historial no crezca sin límite. */
  pruneDone(owner: string, keep?: number): Promise<void>
}

/** Almacén en memoria: para las pruebas y para navegadores sin IndexedDB. */
export class MemoryPendingStore implements PendingStore {
  private ops: PendingOp[] = []
  private next = 1

  async add(op: Omit<PendingOp, 'id'>): Promise<PendingOp> {
    const saved = { ...op, id: this.next++ }
    this.ops.push(saved)
    return { ...saved }
  }

  async update(op: PendingOp): Promise<void> {
    const i = this.ops.findIndex((o) => o.id === op.id)
    if (i >= 0) this.ops[i] = { ...op }
  }

  async delete(id: number): Promise<void> {
    this.ops = this.ops.filter((o) => o.id !== id)
  }

  async all(owner: string): Promise<PendingOp[]> {
    return this.ops.filter((o) => o.owner === owner).map((o) => ({ ...o }))
  }

  async recoverInterrupted(): Promise<void> {
    this.ops = this.ops.map((o) => (o.status === 'SYNCING' ? { ...o, status: 'PENDING' } : o))
  }

  async pruneDone(owner: string, keep = 20): Promise<void> {
    const done = this.ops.filter((o) => o.owner === owner && o.status === 'DONE')
    const drop = new Set(done.slice(0, Math.max(0, done.length - keep)).map((o) => o.id))
    this.ops = this.ops.filter((o) => !drop.has(o.id))
  }
}

const STORE = 'pending_ops'

/** Convierte una petición de IndexedDB en una promesa. */
function request<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

/**
 * Las instrucciones pendientes en IndexedDB: sobreviven a recargar la página, a cerrar el navegador y a que se
 * apague el equipo, que es justo cuando hacen falta.
 */
export class IndexedDbPendingStore implements PendingStore {
  private db: Promise<IDBDatabase> | null = null
  private readonly factory: IDBFactory
  private readonly name: string

  constructor(factory: IDBFactory = indexedDB, name = 'diagramas-offline') {
    this.factory = factory
    this.name = name
  }

  private open(): Promise<IDBDatabase> {
    this.db ??= new Promise((resolve, reject) => {
      const req = this.factory.open(this.name, 1)
      req.onupgradeneeded = () => {
        const store = req.result.createObjectStore(STORE, { keyPath: 'id', autoIncrement: true })
        store.createIndex('owner', 'owner')
      }
      req.onsuccess = () => resolve(req.result)
      req.onerror = () => reject(req.error)
    })
    return this.db
  }

  /** Ejecuta `work` dentro de una transacción y espera a que se confirme. */
  private async run<T>(mode: IDBTransactionMode, work: (store: IDBObjectStore) => Promise<T>): Promise<T> {
    const db = await this.open()
    const tx = db.transaction(STORE, mode)
    const done = new Promise<void>((resolve, reject) => {
      tx.oncomplete = () => resolve()
      tx.onerror = () => reject(tx.error)
      tx.onabort = () => reject(tx.error)
    })
    const result = await work(tx.objectStore(STORE))
    await done
    return result
  }

  async add(op: Omit<PendingOp, 'id'>): Promise<PendingOp> {
    const id = await this.run('readwrite', (s) => request(s.add(op)))
    return { ...op, id: Number(id) }
  }

  async update(op: PendingOp): Promise<void> {
    await this.run('readwrite', (s) => request(s.put(op)))
  }

  async delete(id: number): Promise<void> {
    await this.run('readwrite', (s) => request(s.delete(id)))
  }

  async all(owner: string): Promise<PendingOp[]> {
    const rows = await this.run('readonly', (s) => request(s.index('owner').getAll(owner)))
    // El orden de creación manda: `id` crece siempre, así que es más fiable que la fecha.
    return (rows as PendingOp[]).sort((a, b) => (a.id ?? 0) - (b.id ?? 0))
  }

  async recoverInterrupted(): Promise<void> {
    await this.run('readwrite', async (s) => {
      const rows = (await request(s.getAll())) as PendingOp[]
      await Promise.all(rows.filter((o) => o.status === 'SYNCING').map((o) => request(s.put({ ...o, status: 'PENDING' }))))
    })
  }

  async pruneDone(owner: string, keep = 20): Promise<void> {
    const done = (await this.all(owner)).filter((o) => o.status === 'DONE')
    const drop = done.slice(0, Math.max(0, done.length - keep))
    await this.run('readwrite', async (s) => {
      await Promise.all(drop.map((o) => request(s.delete(o.id as number))))
    })
  }
}
