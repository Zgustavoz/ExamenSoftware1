import {
  applyEdgeChanges,
  applyNodeChanges,
  Background,
  ConnectionMode,
  Controls,
  MiniMap,
  ReactFlow,
  type Connection,
  type EdgeChange,
  type EdgeTypes,
  type NodeChange,
  type NodeTypes,
  type OnSelectionChangeParams,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ClassContent } from '@/lib/diagram/types'
import { UmlClassNode, type UmlNode } from './UmlClassNode'
import { UmlMarkers, UmlRelationshipEdge, type UmlEdge } from './UmlRelationshipEdge'

const nodeTypes: NodeTypes = { umlClass: UmlClassNode }
// Constante, no un `{}` por defecto en los parámetros: ese crearía un objeto nuevo en cada render y el
// efecto que vuelca el contenido se dispararía sin parar.
const NO_LOCKS: Record<string, string> = {}
const edgeTypes: EdgeTypes = { umlRelationship: UmlRelationshipEdge }

interface Props {
  content: ClassContent
  /** Bloqueos activos por elemento: `elementId → nombre de quien edita` (CU-17). */
  locks?: Record<string, string>
  editable?: boolean
  onNodesChange?: (changes: NodeChange<UmlNode>[]) => void
  onConnect?: (connection: Connection) => void
  onSelectionChange?: (params: OnSelectionChangeParams) => void
}

/** Convierte `content_json` en el grafo de React Flow. Los ids son siempre los del servidor. */
export function contentToGraph(content: ClassContent, locks: Record<string, string> = NO_LOCKS) {
  const nodes: UmlNode[] = content.classes.map((uml) => ({
    id: uml.id,
    type: 'umlClass',
    position: { x: uml.x, y: uml.y },
    data: { uml, lockedBy: locks[uml.id] ?? null },
    draggable: locks[uml.id] === undefined,
  }))

  const edges: UmlEdge[] = content.relationships.map((relationship) => ({
    id: relationship.id,
    type: 'umlRelationship',
    source: relationship.sourceId,
    target: relationship.targetId,
    data: { relationship },
  }))

  return { nodes, edges }
}

interface Selectable {
  id: string
  selected?: boolean
}

/**
 * Vuelca el grafo que viene del servidor conservando lo que el usuario tenga seleccionado. Sin esto, cada
 * operación difundida (agregar un atributo, por ejemplo) devolvería elementos nuevos sin marcar, React Flow
 * avisaría de que ya no hay nada seleccionado y el panel de propiedades se cerraría solo.
 */
export function withSelectionPreserved<T extends Selectable>(previous: T[], next: T[]): T[] {
  const selected = new Set(previous.filter((element) => element.selected).map((element) => element.id))
  return next.map((element) => (selected.has(element.id) ? { ...element, selected: true } : element))
}

/**
 * Lienzo compartido por el visor (CU-11) y el editor (CU-07).
 *
 * El grafo vive en el estado del lienzo, no se deriva del contenido en cada render: React Flow necesita
 * poder mover un nodo mientras se arrastra, y si el `content_json` volviera a imponer la posición en cada
 * render el nodo se resistiría al arrastre. El contenido del servidor se vuelca aquí cuando cambia, que es
 * cuando llega una operación difundida, conservando lo que el usuario tenga seleccionado.
 */
export function DiagramCanvas({
  content,
  locks = NO_LOCKS,
  editable = false,
  onNodesChange,
  onConnect,
  onSelectionChange,
}: Props) {
  const graph = useMemo(() => contentToGraph(content, locks), [content, locks])
  const [nodes, setNodes] = useState<UmlNode[]>(graph.nodes)
  const [edges, setEdges] = useState<UmlEdge[]>(graph.edges)

  useEffect(() => {
    // oxlint-disable-next-line react/set-state-in-effect
    setNodes((previous) => withSelectionPreserved(previous, graph.nodes))
    // oxlint-disable-next-line react/set-state-in-effect
    setEdges((previous) => withSelectionPreserved(previous, graph.edges))
  }, [graph])

  const handleNodesChange = useCallback(
    (changes: NodeChange<UmlNode>[]) => {
      setNodes((previous) => applyNodeChanges(changes, previous))
      onNodesChange?.(changes)
    },
    [onNodesChange],
  )

  const handleEdgesChange = useCallback((changes: EdgeChange<UmlEdge>[]) => {
    setEdges((previous) => applyEdgeChanges(changes, previous))
  }, [])

  return (
    <div className="relative size-full">
      <UmlMarkers />
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        onNodesChange={handleNodesChange}
        onEdgesChange={handleEdgesChange}
        onConnect={onConnect}
        onSelectionChange={onSelectionChange}
        // Con un solo punto de conexión por lado, el modo flexible deja empezar y terminar en cualquiera.
        connectionMode={ConnectionMode.Loose}
        nodesDraggable={editable}
        nodesConnectable={editable}
        elementsSelectable
        edgesReconnectable={false}
        // Borrar solo por la acción explícita: cada baja tiene que pasar por el vocabulario de operaciones
        // y por la validación del servidor, nunca por una tecla.
        deleteKeyCode={null}
        fitView
        fitViewOptions={{ padding: 0.2, maxZoom: 1 }}
        minZoom={0.1}
        maxZoom={2}
        proOptions={{ hideAttribution: true }}
      >
        <Background />
        <Controls showInteractive={false} />
        <MiniMap pannable zoomable className="!bg-secondary" />
      </ReactFlow>
    </div>
  )
}
