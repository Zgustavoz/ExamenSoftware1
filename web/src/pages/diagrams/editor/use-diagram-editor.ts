import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { toast } from 'sonner'
import { getDiagram, saveDiagram, versionConflictDetails } from '@/lib/api/diagrams'
import { ApiError, errorMessage } from '@/lib/api/errors'
import { CollabSession, type CollabMessage, type Participant } from '@/lib/collab/collab-client'
import { applyOperation } from '@/lib/diagram/apply'
import type { DiagramOperation } from '@/lib/diagram/operations'
import { isClassContent, type ClassContent } from '@/lib/diagram/types'
import { useAuthStore } from '@/stores/auth-store'

interface EditorState {
  name: string
  /** Proyecto al que pertenece, para poder volver a él desde el editor. */
  projectId: string | null
  content: ClassContent | null
  version: number
  /** `elementId → nombre de quien lo tiene bloqueado`, sin contar los bloqueos propios. */
  locks: Record<string, string>
  participants: Participant[]
  connected: boolean
}

const INITIAL: EditorState = { name: '', projectId: null, content: null, version: 0, locks: {}, participants: [], connected: false }

/**
 * Estado del editor (CU-07 … CU-10, CU-17). Toda edición se envía como operación por WebSocket: el servidor
 * valida, aplica, incrementa la versión y difunde el resultado, que es lo que se refleja aquí. El cliente
 * nunca modifica `content_json` por su cuenta.
 */
export function useDiagramEditor(diagramId: string) {
  const token = useAuthStore((s) => s.token)
  const currentUserId = useAuthStore((s) => s.user?.id)
  const queryClient = useQueryClient()

  const [state, setState] = useState<EditorState>(INITIAL)
  const [loadError, setLoadError] = useState<unknown>(null)
  const [saving, setSaving] = useState(false)
  const session = useRef<CollabSession | null>(null)
  // La versión vive también en una referencia: los mensajes llegan fuera del ciclo de render.
  const version = useRef(0)

  const load = useCallback(async () => {
    try {
      const diagram = await getDiagram(diagramId)
      if (!diagram) {
        setLoadError(new ApiError('NOT_FOUND', 'El diagrama no existe o no tiene acceso.', 404))
        return
      }
      if (!isClassContent(diagram.contentJson)) {
        setLoadError(new ApiError('VALIDATION_ERROR', 'Este diagrama no es de clases.', 422))
        return
      }
      version.current = diagram.version
      setState((prev) => ({
        ...prev,
        name: diagram.name,
        projectId: diagram.projectId,
        content: diagram.contentJson as ClassContent,
        version: diagram.version,
      }))
      setLoadError(null)
    } catch (error) {
      setLoadError(error)
    }
  }, [diagramId])

  useEffect(() => {
    // `load` es asíncrona: el estado se actualiza al volver del servidor, no durante este render.
    // oxlint-disable-next-line react/set-state-in-effect
    void load()
  }, [load])

  const onMessage = useCallback(
    (message: CollabMessage) => {
      if (message.type === 'JOIN' || message.type === 'LEAVE') {
        setState((prev) => ({ ...prev, participants: message.participants ?? prev.participants }))
        return
      }

      if (message.type === 'LOCK' || message.type === 'UNLOCK') {
        if (!message.elementId || message.userId === currentUserId) return
        setState((prev) => {
          const locks = { ...prev.locks }
          if (message.type === 'LOCK') locks[message.elementId!] = message.username
          else delete locks[message.elementId!]
          return { ...prev, locks }
        })
        return
      }

      if (message.type !== 'OP' || !message.op || message.version == null) return

      // Si falta algún mensaje intermedio, el estado local ya no es fiable: se recarga entero por versión.
      if (message.version !== version.current + 1) {
        void load()
        return
      }
      version.current = message.version
      setState((prev) =>
        prev.content
          ? { ...prev, content: applyOperation(prev.content, message.op!), version: message.version! }
          : prev,
      )
    },
    [currentUserId, load],
  )

  useEffect(() => {
    if (!token) return
    const collab = new CollabSession(diagramId, token, {
      onMessage,
      onError: (error) => toast.error(errorMessage(error)),
      onConnectedChange: (connected) => {
        setState((prev) => ({ ...prev, connected }))
        // Al reconectar puede haberse perdido algún cambio: se pide el estado completo.
        if (connected) void load()
      },
    })
    session.current = collab
    collab.activate()

    return () => {
      session.current = null
      void collab.deactivate()
    }
  }, [diagramId, token, onMessage, load])

  /** Envía una operación. El resultado llega por difusión, no se aplica de forma optimista. */
  const send = useCallback((operation: DiagramOperation) => {
    if (!session.current?.connected) {
      toast.error('Sin conexión con el servidor. Los cambios no se están guardando.')
      return false
    }
    session.current.sendOperation(operation)
    return true
  }, [])

  const lock = useCallback((elementId: string) => session.current?.lock(elementId), [])
  const unlock = useCallback((elementId: string) => session.current?.unlock(elementId), [])

  /**
   * CU-10 Guardar explícitamente, con control optimista. Ante `VERSION_CONFLICT` se adopta el estado del
   * servidor, que viene en `details`.
   */
  const save = useCallback(async () => {
    if (!state.content) return
    setSaving(true)
    try {
      const saved = await saveDiagram({ id: diagramId, contentJson: state.content, baseVersion: version.current })
      version.current = saved.version
      setState((prev) => ({ ...prev, version: saved.version }))
      await queryClient.invalidateQueries({ queryKey: ['diagram', diagramId] })
      toast.success('Diagrama guardado.')
    } catch (error) {
      if (error instanceof ApiError && error.is('VERSION_CONFLICT')) {
        const conflict = versionConflictDetails(error.details)
        if (conflict && isClassContent(conflict.contentJson)) {
          version.current = conflict.currentVersion
          setState((prev) => ({
            ...prev,
            content: conflict.contentJson as ClassContent,
            version: conflict.currentVersion,
          }))
          toast.warning('Otra persona modificó el diagrama: se cargó la versión más reciente.')
          return
        }
        await load()
        toast.warning('Otra persona modificó el diagrama: se cargó la versión más reciente.')
        return
      }
      toast.error(errorMessage(error))
    } finally {
      setSaving(false)
    }
  }, [diagramId, state.content, queryClient, load])

  const classes = useMemo(() => state.content?.classes ?? [], [state.content])

  return { ...state, classes, loadError, saving, send, lock, unlock, save, reload: load }
}
