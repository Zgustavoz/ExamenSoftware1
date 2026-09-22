import { useEffect, useSyncExternalStore } from 'react'
import { toast } from 'sonner'
import { sendAiInstruction } from '@/lib/api/copilot'
import { queryClient } from '@/lib/query-client'
import { useAuthStore } from '@/stores/auth-store'
import { IndexedDbPendingStore, MemoryPendingStore } from './pending-store'
import { browserConnectivity, browserLock, OfflineQueue, type QueueState } from './queue'

/**
 * La cola de instrucciones pendientes de toda la aplicación: una sola, para que se envíen al volver la conexión
 * aunque el usuario ya haya cambiado de pantalla. Sin IndexedDB (entornos sin navegador) usa memoria.
 */
export const offlineQueue = new OfflineQueue({
  store: typeof indexedDB === 'undefined' ? new MemoryPendingStore() : new IndexedDbPendingStore(),
  send: (input) => sendAiInstruction(input),
  connectivity: browserConnectivity,
  lock: browserLock,
})

/** El estado de la cola (conexión, envío en curso, instrucciones guardadas) para pintarlo. */
export function useOfflineQueue(queue: OfflineQueue = offlineQueue): QueueState {
  return useSyncExternalStore(queue.subscribe, queue.getSnapshot)
}

/**
 * Se monta una vez en el marco con sesión: asocia la cola al usuario, la pone a escuchar la red y avisa cuando se
 * envían instrucciones guardadas.
 */
export function useOfflineSync(queue: OfflineQueue = offlineQueue): void {
  const user = useAuthStore((s) => s.user)
  const owner = user ? `${user.companyId}/${user.id}` : null

  useEffect(() => {
    queue.start()
    return () => queue.stop()
  }, [queue])

  useEffect(() => {
    void queue.setOwner(owner)
  }, [queue, owner])

  useEffect(
    () =>
      queue.onSynced((done) => {
        toast.success(done.length === 1 ? 'Se envió 1 instrucción guardada al asistente.' : `Se enviaron ${done.length} instrucciones guardadas al asistente.`)
        for (const diagramId of new Set(done.map((o) => o.diagramId))) {
          void queryClient.invalidateQueries({ queryKey: ['aiChats', diagramId] })
        }
      }),
    [queue],
  )
}
