import { Handle, Position, type NodeProps, type Node } from '@xyflow/react'
import { Lock } from 'lucide-react'
import { memo } from 'react'
import { cn } from '@/lib/utils'
import { methodSignature, VISIBILITY_SYMBOL, type UmlClass } from '@/lib/diagram/types'

export interface UmlNodeData extends Record<string, unknown> {
  uml: UmlClass
  /** Nombre de quien tiene bloqueado el elemento (CU-17); `null` si está libre. */
  lockedBy: string | null
}

export type UmlNode = Node<UmlNodeData, 'umlClass'>

/**
 * Un punto de conexión por lado, para enlazar por donde quede más natural. Hay uno solo, no uno de origen y
 * otro de destino: si se apilan dos en la misma posición, el de arriba intercepta el puntero y no se puede
 * soltar la conexión sobre el de abajo. El lienzo usa `ConnectionMode.Loose`, que permite empezar y terminar
 * en cualquiera de ellos.
 */
const SIDES = [
  { position: Position.Top, id: 'top' },
  { position: Position.Right, id: 'right' },
  { position: Position.Bottom, id: 'bottom' },
  { position: Position.Left, id: 'left' },
] as const

/** Una clase UML: nombre (con estereotipo), atributos y métodos, en tres compartimentos. */
function UmlClassNodeComponent({ data, selected }: NodeProps<UmlNode>) {
  const { uml, lockedBy } = data
  const isInterface = uml.stereotype === 'interface'
  const isAbstract = uml.stereotype === 'abstract'

  return (
    <div
      className={cn(
        'w-56 overflow-hidden rounded-lg border-2 bg-card text-card-foreground shadow-sm',
        selected ? 'border-primary' : 'border-foreground/25',
        lockedBy && 'opacity-60 ring-2 ring-amber-400',
      )}
    >
      {SIDES.map(({ position, id }) => (
        <Handle key={id} type="source" position={position} id={id} className="!size-2.5 !border-0 !bg-primary/60" />
      ))}

      <header className="border-b bg-secondary/60 px-3 py-2 text-center">
        {uml.stereotype && uml.stereotype !== 'abstract' && (
          <p className="text-[11px] text-muted-foreground">«{uml.stereotype}»</p>
        )}
        <p className={cn('font-medium', (isInterface || isAbstract) && 'italic')}>{uml.name}</p>
        {lockedBy && (
          <p className="mt-1 flex items-center justify-center gap-1 text-[11px] text-amber-700">
            <Lock className="size-3" aria-hidden />
            {lockedBy} está editando
          </p>
        )}
      </header>

      <ul className="min-h-6 border-b px-3 py-1.5 text-xs">
        {uml.attributes.map((attribute) => (
          <li key={attribute.id} className="truncate font-mono">
            {VISIBILITY_SYMBOL[attribute.visibility]} {attribute.name}: {attribute.type}
          </li>
        ))}
      </ul>

      <ul className="min-h-6 px-3 py-1.5 text-xs">
        {uml.methods.map((method) => (
          <li key={method.id} className="truncate font-mono">
            {VISIBILITY_SYMBOL[method.visibility]} {methodSignature(method)}
          </li>
        ))}
      </ul>
    </div>
  )
}

export const UmlClassNode = memo(UmlClassNodeComponent)
