import { BaseEdge, EdgeLabelRenderer, getSmoothStepPath, type Edge, type EdgeProps } from '@xyflow/react'
import { memo } from 'react'
import type { Relationship, RelationshipType } from '@/lib/diagram/types'

export interface UmlEdgeData extends Record<string, unknown> {
  relationship: Relationship
}

export type UmlEdge = Edge<UmlEdgeData, 'umlRelationship'>

/**
 * Notación UML de cada relación. El rombo va en el extremo «todo» (el origen) y el triángulo en el extremo
 * general (el destino), según el estándar.
 */
const STYLE: Record<RelationshipType, { dashed: boolean; markerStart?: string; markerEnd?: string }> = {
  ASSOCIATION: { dashed: false, markerEnd: 'url(#uml-open-arrow)' },
  AGGREGATION: { dashed: false, markerStart: 'url(#uml-diamond-hollow)' },
  COMPOSITION: { dashed: false, markerStart: 'url(#uml-diamond-filled)' },
  GENERALIZATION: { dashed: false, markerEnd: 'url(#uml-triangle-hollow)' },
  REALIZATION: { dashed: true, markerEnd: 'url(#uml-triangle-hollow)' },
  DEPENDENCY: { dashed: true, markerEnd: 'url(#uml-open-arrow)' },
}

/** Etiqueta flotante (multiplicidad o rol) anclada a un punto del trazado. */
function EdgeLabel({ x, y, children }: { x: number; y: number; children: string }) {
  return (
    <div
      style={{ transform: `translate(-50%, -50%) translate(${x}px, ${y}px)` }}
      className="pointer-events-none absolute rounded bg-background/90 px-1 text-[11px] text-muted-foreground"
    >
      {children}
    </div>
  )
}

function UmlRelationshipEdgeComponent({
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  data,
  selected,
}: EdgeProps<UmlEdge>) {
  const relationship = data?.relationship
  const style = STYLE[relationship?.type ?? 'ASSOCIATION']

  const [path, labelX, labelY] = getSmoothStepPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
    borderRadius: 8,
  })

  // Las multiplicidades se colocan cerca de su extremo, no en el medio, para no confundir cuál es cuál.
  const nearSource = { x: sourceX + (labelX - sourceX) * 0.18, y: sourceY + (labelY - sourceY) * 0.18 }
  const nearTarget = { x: targetX + (labelX - targetX) * 0.18, y: targetY + (labelY - targetY) * 0.18 }

  const sourceLabel = [relationship?.sourceMultiplicity, relationship?.sourceRole].filter(Boolean).join(' ')
  const targetLabel = [relationship?.targetMultiplicity, relationship?.targetRole].filter(Boolean).join(' ')

  return (
    <>
      <BaseEdge
        path={path}
        markerStart={style.markerStart}
        markerEnd={style.markerEnd}
        style={{
          stroke: selected ? 'var(--color-primary)' : 'var(--color-foreground)',
          strokeWidth: selected ? 2 : 1.5,
          strokeDasharray: style.dashed ? '6 4' : undefined,
        }}
      />
      <EdgeLabelRenderer>
        {relationship?.name && (
          <EdgeLabel x={labelX} y={labelY}>
            {relationship.name}
          </EdgeLabel>
        )}
        {sourceLabel && (
          <EdgeLabel x={nearSource.x} y={nearSource.y}>
            {sourceLabel}
          </EdgeLabel>
        )}
        {targetLabel && (
          <EdgeLabel x={nearTarget.x} y={nearTarget.y}>
            {targetLabel}
          </EdgeLabel>
        )}
      </EdgeLabelRenderer>
    </>
  )
}

export const UmlRelationshipEdge = memo(UmlRelationshipEdgeComponent)

/**
 * Marcadores UML. React Flow solo acepta `markerStart` / `markerEnd` como referencia a un `<marker>` del
 * documento, así que se definen una vez por lienzo.
 */
export function UmlMarkers() {
  return (
    <svg className="absolute size-0" aria-hidden>
      <defs>
        <marker
          id="uml-triangle-hollow"
          viewBox="0 0 12 12"
          refX="11"
          refY="6"
          markerWidth="11"
          markerHeight="11"
          orient="auto-start-reverse"
        >
          <path d="M 1 1 L 11 6 L 1 11 z" fill="var(--color-card)" stroke="var(--color-foreground)" strokeWidth="1.2" />
        </marker>
        <marker
          id="uml-diamond-hollow"
          viewBox="0 0 16 12"
          refX="1"
          refY="6"
          markerWidth="14"
          markerHeight="11"
          orient="auto-start-reverse"
        >
          <path
            d="M 1 6 L 8 1 L 15 6 L 8 11 z"
            fill="var(--color-card)"
            stroke="var(--color-foreground)"
            strokeWidth="1.2"
          />
        </marker>
        <marker
          id="uml-diamond-filled"
          viewBox="0 0 16 12"
          refX="1"
          refY="6"
          markerWidth="14"
          markerHeight="11"
          orient="auto-start-reverse"
        >
          <path d="M 1 6 L 8 1 L 15 6 L 8 11 z" fill="var(--color-foreground)" />
        </marker>
        <marker
          id="uml-open-arrow"
          viewBox="0 0 12 12"
          refX="10"
          refY="6"
          markerWidth="10"
          markerHeight="10"
          orient="auto-start-reverse"
        >
          <path d="M 2 1 L 10 6 L 2 11" fill="none" stroke="var(--color-foreground)" strokeWidth="1.4" />
        </marker>
      </defs>
    </svg>
  )
}
