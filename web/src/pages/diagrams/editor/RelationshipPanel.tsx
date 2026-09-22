import { Trash2 } from 'lucide-react'
import { useId } from 'react'
import { ConfirmButton } from '@/components/ConfirmButton'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Separator } from '@/components/ui/separator'
import type { DiagramOperation } from '@/lib/diagram/operations'
import {
  MULTIPLICITY_PATTERN,
  RELATIONSHIP_LABEL,
  RELATIONSHIP_TYPES,
  type Relationship,
  type RelationshipType,
  type UmlClass,
} from '@/lib/diagram/types'

interface Props {
  relationship: Relationship
  classes: UmlClass[]
  readOnly: boolean
  send: (operation: DiagramOperation) => boolean
}

/** CU-09 Gestionar relaciones: tipo, multiplicidades y roles de una relación ya creada. */
export function RelationshipPanel({ relationship, classes, readOnly, send }: Props) {
  const id = useId()
  const nameOf = (classId: string) => classes.find((c) => c.id === classId)?.name ?? '—'

  const update = (changes: Partial<Omit<Relationship, 'id' | 'sourceId' | 'targetId'>>) =>
    send({ op: 'UPDATE_RELATIONSHIP', relationshipId: relationship.id, changes })

  /** El backend rechaza un formato inválido; aquí se evita enviarlo siquiera. */
  const updateMultiplicity = (end: 'sourceMultiplicity' | 'targetMultiplicity', value: string) => {
    const trimmed = value.trim()
    if (trimmed && !MULTIPLICITY_PATTERN.test(trimmed)) return
    update({ [end]: trimmed || null })
  }

  return (
    <div className="grid gap-4">
      <p className="text-sm text-muted-foreground">
        {nameOf(relationship.sourceId)} → {nameOf(relationship.targetId)}
      </p>

      <div className="grid gap-2">
        <Label htmlFor={`${id}-type`}>Tipo</Label>
        <Select
          value={relationship.type}
          disabled={readOnly}
          onValueChange={(value) => update({ type: value as RelationshipType })}
        >
          <SelectTrigger id={`${id}-type`}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {RELATIONSHIP_TYPES.map((type) => (
              <SelectItem key={type} value={type}>
                {RELATIONSHIP_LABEL[type]}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="grid gap-2">
        <Label htmlFor={`${id}-name`}>Nombre de la relación</Label>
        <Input
          id={`${id}-name`}
          key={relationship.name ?? ''}
          defaultValue={relationship.name ?? ''}
          disabled={readOnly}
          onBlur={(e) => e.target.value.trim() !== (relationship.name ?? '') && update({ name: e.target.value.trim() || null })}
        />
      </div>

      <Separator />

      <div className="grid grid-cols-2 gap-3">
        <div className="grid gap-2">
          <Label htmlFor={`${id}-source-mult`}>Multiplicidad de origen</Label>
          <Input
            id={`${id}-source-mult`}
            key={`src-${relationship.sourceMultiplicity ?? ''}`}
            defaultValue={relationship.sourceMultiplicity ?? ''}
            placeholder="1, 0..*, *"
            disabled={readOnly}
            onBlur={(e) => updateMultiplicity('sourceMultiplicity', e.target.value)}
          />
        </div>

        <div className="grid gap-2">
          <Label htmlFor={`${id}-target-mult`}>Multiplicidad de destino</Label>
          <Input
            id={`${id}-target-mult`}
            key={`tgt-${relationship.targetMultiplicity ?? ''}`}
            defaultValue={relationship.targetMultiplicity ?? ''}
            placeholder="1, 0..*, *"
            disabled={readOnly}
            onBlur={(e) => updateMultiplicity('targetMultiplicity', e.target.value)}
          />
        </div>

        <div className="grid gap-2">
          <Label htmlFor={`${id}-source-role`}>Rol de origen</Label>
          <Input
            id={`${id}-source-role`}
            key={`srcr-${relationship.sourceRole ?? ''}`}
            defaultValue={relationship.sourceRole ?? ''}
            disabled={readOnly}
            onBlur={(e) => update({ sourceRole: e.target.value.trim() || null })}
          />
        </div>

        <div className="grid gap-2">
          <Label htmlFor={`${id}-target-role`}>Rol de destino</Label>
          <Input
            id={`${id}-target-role`}
            key={`tgtr-${relationship.targetRole ?? ''}`}
            defaultValue={relationship.targetRole ?? ''}
            disabled={readOnly}
            onBlur={(e) => update({ targetRole: e.target.value.trim() || null })}
          />
        </div>
      </div>

      <Separator />

      <ConfirmButton
        title="¿Eliminar la relación?"
        description={`Se quitará la conexión entre ${nameOf(relationship.sourceId)} y ${nameOf(relationship.targetId)}. No se puede deshacer.`}
        confirmLabel="Eliminar la relación"
        disabled={readOnly}
        onConfirm={() => send({ op: 'REMOVE_RELATIONSHIP', relationshipId: relationship.id })}
      >
        <Trash2 className="size-4" aria-hidden />
        Eliminar la relación
      </ConfirmButton>
    </div>
  )
}
