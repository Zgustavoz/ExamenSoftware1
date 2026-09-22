import { useMutation } from '@tanstack/react-query'
import { ReactFlowProvider, type Connection, type NodeChange } from '@xyflow/react'
import {
  ArrowLeft,
  Bot,
  Code2,
  Download,
  Eye,
  GitBranch,
  ListChecks,
  LoaderCircle,
  Plus,
  Save,
  SlidersHorizontal,
  Users,
  Wifi,
  WifiOff,
} from 'lucide-react'
import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { DiagramCanvas } from '@/components/diagram/DiagramCanvas'
import type { UmlNode } from '@/components/diagram/UmlClassNode'
import { FormError } from '@/components/FormError'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { generateSequenceDiagram } from '@/lib/api/diagrams'
import { errorMessage } from '@/lib/api/errors'
import { exportXmi } from '@/lib/api/xmi'
import type { NewRelationship } from '@/lib/diagram/operations'
import { clampToCanvas } from '@/lib/diagram/types'
import { cn } from '@/lib/utils'
import { ClassPropertiesPanel } from './ClassPropertiesPanel'
import { CopilotPanel } from './CopilotPanel'
import { NewRelationshipDialog } from './NewRelationshipDialog'
import { RelationshipPanel } from './RelationshipPanel'
import { useDiagramEditor } from './use-diagram-editor'

/** CU-07 … CU-10: edición manual del diagrama de clases. */
/** El diálogo solo hace falta al pulsar «Tarea»: así no carga el editor con lo que casi nunca se usa. */
const TaskFormDialog = lazy(() =>
  import('@/pages/tasks/TaskFormDialog').then((m) => ({ default: m.TaskFormDialog })),
)

export default function DiagramEditorPage() {
  const { diagramId = '' } = useParams()
  const navigate = useNavigate()
  const editor = useDiagramEditor(diagramId)
  const { content, classes, locks, participants, connected, loadError, saving, send, save, reload, lock, unlock } =
    editor

  const [selectedClassId, setSelectedClassId] = useState<string | null>(null)
  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null)
  const [pendingConnection, setPendingConnection] = useState<Connection | null>(null)
  const [panel, setPanel] = useState<'properties' | 'copilot'>('properties')
  const [creatingTask, setCreatingTask] = useState(false)

  const held = useRef<string | null>(null)

  /**
   * CU-17: al seleccionar una clase se reserva para editarla, y se libera al cambiar de selección o al
   * salir. Los bloqueos caducan solos a los 30 s, y el servidor los renueva con cada operación.
   */
  useEffect(() => {
    if (held.current === selectedClassId) return
    if (held.current) unlock(held.current)
    // No se pide un elemento que ya tiene otra persona: el servidor respondería ELEMENT_LOCKED.
    held.current = selectedClassId && locks[selectedClassId] === undefined ? selectedClassId : null
    if (held.current) lock(held.current)
  }, [selectedClassId, locks, lock, unlock])

  useEffect(() => {
    return () => {
      if (held.current) unlock(held.current)
    }
  }, [unlock])

  const selectedClass = classes.find((c) => c.id === selectedClassId) ?? null
  const selectedEdge = content?.relationships.find((r) => r.id === selectedEdgeId) ?? null
  const lockedBySomeoneElse = selectedClassId !== null && locks[selectedClassId] !== undefined

  /** Al terminar de arrastrar se envía `MOVE_CLASS`: el servidor lo persiste y lo difunde (CP-02). */
  const onNodesChange = useCallback(
    (changes: NodeChange<UmlNode>[]) => {
      for (const change of changes) {
        if (change.type === 'position' && change.dragging === false && change.position) {
          send({
            op: 'MOVE_CLASS',
            classId: change.id,
            x: clampToCanvas(change.position.x),
            y: clampToCanvas(change.position.y),
          })
        }
      }
    },
    [send],
  )

  const addClass = () =>
    send({
      op: 'ADD_CLASS',
      class: {
        name: `Clase${classes.length + 1}`,
        // Sin x/y el servidor coloca la clase en la grilla; aquí se propone un hueco a la derecha.
        x: clampToCanvas(80 + (classes.length % 4) * 260),
        y: clampToCanvas(80 + Math.floor(classes.length / 4) * 220),
      },
    })

  /** CU-16 Exportar el diagrama a XMI. */
  const exportar = useMutation({
    mutationFn: () => exportXmi(diagramId, editor.name || 'diagrama'),
    onError: (error) => toast.error(errorMessage(error)),
  })

  /** CU-20 Generar el diagrama de secuencia derivado y abrirlo. */
  const secuencia = useMutation({
    mutationFn: () => generateSequenceDiagram(diagramId),
    onSuccess: (diagram) => {
      toast.success('Diagrama de secuencia generado.')
      navigate(`/diagrams/${diagram.id}/view`)
    },
    onError: (error) => toast.error(errorMessage(error)),
  })

  const createRelationship = (relationship: NewRelationship) => {
    send({ op: 'ADD_RELATIONSHIP', relationship })
    setPendingConnection(null)
  }

  if (loadError) {
    return (
      <div className="grid gap-4">
        <FormError error={loadError} />
        <Button asChild variant="link" className="justify-self-start">
          <Link to="/projects">Volver a proyectos</Link>
        </Button>
      </div>
    )
  }

  if (!content) {
    return (
      <p className="flex items-center gap-2 text-muted-foreground">
        <LoaderCircle className="size-4 animate-spin" aria-hidden />
        Cargando diagrama…
      </p>
    )
  }

  return (
    <>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <Button asChild variant="ghost" size="sm" className="-ml-2">
            {/* Al proyecto del diagrama, no a la lista de proyectos: desde ahí se pierde de vista. */}
            <Link to={editor.projectId ? `/projects/${editor.projectId}` : '/projects'}>
              <ArrowLeft className="size-4" aria-hidden />
              Volver al proyecto
            </Link>
          </Button>
          <span className="font-medium">{editor.name}</span>
          <Badge variant="outline">versión {editor.version}</Badge>
          {connected ? (
            <span className="flex items-center gap-1 text-xs text-muted-foreground">
              <Wifi className="size-3.5 text-emerald-600" aria-hidden />
              Conectado
            </span>
          ) : (
            <span className="flex items-center gap-1 text-xs text-amber-700">
              <WifiOff className="size-3.5" aria-hidden />
              Sin conexión
            </span>
          )}
          {participants.length > 0 && (
            <span className="flex items-center gap-1 text-xs text-muted-foreground">
              <Users className="size-3.5" aria-hidden />
              {participants.map((p) => p.username).join(', ')}
            </span>
          )}
        </div>

        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => secuencia.mutate()}
            disabled={secuencia.isPending}
          >
            {secuencia.isPending ? (
              <LoaderCircle className="size-4 animate-spin" aria-hidden />
            ) : (
              <GitBranch className="size-4" aria-hidden />
            )}
            Secuencia
          </Button>
          <Button variant="outline" size="sm" onClick={() => exportar.mutate()} disabled={exportar.isPending}>
            {exportar.isPending ? (
              <LoaderCircle className="size-4 animate-spin" aria-hidden />
            ) : (
              <Download className="size-4" aria-hidden />
            )}
            XMI
          </Button>
          <Button variant="outline" size="sm" onClick={() => setCreatingTask(true)}>
            <ListChecks className="size-4" aria-hidden />
            Tarea
          </Button>
          <Button variant="outline" size="sm" asChild>
            <Link to={`/diagrams/${diagramId}/code`}>
              <Code2 className="size-4" aria-hidden />
              Código
            </Link>
          </Button>
          <Button variant="outline" size="sm" asChild>
            <Link to={`/diagrams/${diagramId}/view`}>
              <Eye className="size-4" aria-hidden />
              Ver
            </Link>
          </Button>
          <Button size="sm" onClick={() => void save()} disabled={saving}>
            {saving ? <LoaderCircle className="size-4 animate-spin" aria-hidden /> : <Save className="size-4" aria-hidden />}
            Guardar
          </Button>
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-[1fr_20rem]">
        <div className="h-[70vh] overflow-hidden rounded-xl border bg-card">
          <div className="flex items-center gap-2 border-b px-3 py-2">
            <Button size="sm" variant="outline" onClick={addClass} disabled={!connected}>
              <Plus className="size-4" aria-hidden />
              Agregar clase
            </Button>
            <p className="text-xs text-muted-foreground">
              Arrastre de un borde a otro para crear una relación.
            </p>
          </div>

          <div className="h-[calc(70vh-3rem)]">
            <ReactFlowProvider>
              <DiagramCanvas
                content={content}
                locks={locks}
                editable
                onNodesChange={onNodesChange}
                onConnect={(connection) => setPendingConnection(connection)}
                onSelectionChange={({ nodes, edges }) => {
                  setSelectedClassId(nodes[0]?.id ?? null)
                  setSelectedEdgeId(edges[0]?.id ?? null)
                }}
              />
            </ReactFlowProvider>
          </div>
        </div>

        <aside className="flex h-[70vh] flex-col rounded-xl border bg-card">
          <div className="flex gap-1 border-b p-2">
            <Button
              size="sm"
              variant="ghost"
              className={cn('flex-1', panel === 'properties' && 'bg-accent text-accent-foreground')}
              onClick={() => setPanel('properties')}
            >
              <SlidersHorizontal className="size-4" aria-hidden />
              Propiedades
            </Button>
            <Button
              size="sm"
              variant="ghost"
              className={cn('flex-1', panel === 'copilot' && 'bg-accent text-accent-foreground')}
              onClick={() => setPanel('copilot')}
            >
              <Bot className="size-4" aria-hidden />
              Asistente
            </Button>
          </div>

          <div className="min-h-0 flex-1 overflow-y-auto p-4">
          {panel === 'copilot' ? (
            <CopilotPanel diagramId={diagramId} onApplied={() => void reload()} />
          ) : selectedClass ? (
            <>
              <h2 className="mb-4 font-medium">Clase</h2>
              {lockedBySomeoneElse && (
                <p className="mb-3 rounded-md bg-amber-50 p-2 text-xs text-amber-800">
                  {locks[selectedClass.id]} está editando esta clase.
                </p>
              )}
              <ClassPropertiesPanel
                uml={selectedClass}
                readOnly={!connected || lockedBySomeoneElse}
                send={send}
                otherClassNames={classes.filter((c) => c.id !== selectedClass.id).map((c) => c.name)}
              />
            </>
          ) : selectedEdge ? (
            <>
              <h2 className="mb-4 font-medium">Relación</h2>
              <RelationshipPanel
                relationship={selectedEdge}
                classes={classes}
                readOnly={!connected}
                send={send}
              />
            </>
          ) : (
            <p className="text-sm text-muted-foreground">
              Seleccione una clase o una relación para ver sus propiedades.
            </p>
          )}
          </div>
        </aside>
      </div>

      {pendingConnection?.source && pendingConnection.target && (
        <NewRelationshipDialog
          sourceId={pendingConnection.source}
          targetId={pendingConnection.target}
          classes={classes}
          onCancel={() => setPendingConnection(null)}
          onCreate={createRelationship}
        />
      )}

      {/* CU-21: crear una tarea sin salir del diagrama que se está editando. */}
      {creatingTask && (
        <Suspense fallback={null}>
          <TaskFormDialog open diagramId={diagramId} onOpenChange={(open) => !open && setCreatingTask(false)} />
        </Suspense>
      )}
    </>
  )
}
