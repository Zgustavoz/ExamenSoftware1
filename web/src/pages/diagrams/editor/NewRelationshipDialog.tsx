import { useState, type FormEvent } from 'react'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import type { NewRelationship } from '@/lib/diagram/operations'
import {
  MULTIPLICITY_PATTERN,
  RELATIONSHIP_LABEL,
  RELATIONSHIP_TYPES,
  type RelationshipType,
  type UmlClass,
} from '@/lib/diagram/types'

interface Props {
  sourceId: string
  targetId: string
  classes: UmlClass[]
  onCancel: () => void
  onCreate: (relationship: NewRelationship) => void
}

/** CU-09: al trazar una conexión en el lienzo se pregunta de qué tipo es antes de enviarla. */
export function NewRelationshipDialog({ sourceId, targetId, classes, onCancel, onCreate }: Props) {
  const [type, setType] = useState<RelationshipType>('ASSOCIATION')
  const [sourceMultiplicity, setSourceMultiplicity] = useState('1')
  const [targetMultiplicity, setTargetMultiplicity] = useState('0..*')

  const nameOf = (id: string) => classes.find((c) => c.id === id)?.name ?? '—'
  // La herencia y la realización no llevan multiplicidad.
  const withMultiplicity = type !== 'GENERALIZATION' && type !== 'REALIZATION'
  const validMultiplicities =
    !withMultiplicity ||
    ((!sourceMultiplicity || MULTIPLICITY_PATTERN.test(sourceMultiplicity)) &&
      (!targetMultiplicity || MULTIPLICITY_PATTERN.test(targetMultiplicity)))

  const submit = (event: FormEvent) => {
    event.preventDefault()
    onCreate({
      type,
      sourceId,
      targetId,
      sourceMultiplicity: withMultiplicity ? sourceMultiplicity || null : null,
      targetMultiplicity: withMultiplicity ? targetMultiplicity || null : null,
    })
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onCancel()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Nueva relación</DialogTitle>
          <DialogDescription>
            De {nameOf(sourceId)} a {nameOf(targetId)}.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={submit} className="grid gap-4">
          <div className="grid gap-2">
            <Label htmlFor="rel-type">Tipo</Label>
            <Select value={type} onValueChange={(value) => setType(value as RelationshipType)}>
              <SelectTrigger id="rel-type">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {RELATIONSHIP_TYPES.map((option) => (
                  <SelectItem key={option} value={option}>
                    {RELATIONSHIP_LABEL[option]}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {withMultiplicity && (
            <div className="grid grid-cols-2 gap-3">
              <div className="grid gap-2">
                <Label htmlFor="rel-source-mult">Multiplicidad de origen</Label>
                <Input
                  id="rel-source-mult"
                  value={sourceMultiplicity}
                  onChange={(e) => setSourceMultiplicity(e.target.value)}
                  placeholder="1"
                />
              </div>
              <div className="grid gap-2">
                <Label htmlFor="rel-target-mult">Multiplicidad de destino</Label>
                <Input
                  id="rel-target-mult"
                  value={targetMultiplicity}
                  onChange={(e) => setTargetMultiplicity(e.target.value)}
                  placeholder="0..*"
                />
              </div>
              {!validMultiplicities && (
                <p className="col-span-2 text-xs text-destructive">
                  Use un formato como 1, 0..1, 0..* o *.
                </p>
              )}
            </div>
          )}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={onCancel}>
              Cancelar
            </Button>
            <Button type="submit" disabled={!validMultiplicities}>
              Crear relación
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
