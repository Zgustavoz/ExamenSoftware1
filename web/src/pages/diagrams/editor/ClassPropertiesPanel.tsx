import { Plus, Trash2 } from 'lucide-react'
import { useId } from 'react'
import { ConfirmButton } from '@/components/ConfirmButton'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Separator } from '@/components/ui/separator'
import { formatParameters, parseParameters } from '@/lib/diagram/parameters'
import type { DiagramOperation } from '@/lib/diagram/operations'
import {
  DATA_TYPES,
  STEREOTYPES,
  VISIBILITIES,
  VISIBILITY_LABEL,
  type Method,
  type Stereotype,
  type UmlClass,
  type Visibility,
} from '@/lib/diagram/types'

interface Props {
  uml: UmlClass
  readOnly: boolean
  send: (operation: DiagramOperation) => boolean
  /** Nombres de las demás clases del diagrama: también valen como tipo de un atributo. */
  otherClassNames?: string[]
}

const STEREOTYPE_LABEL: Record<Stereotype, string> = {
  interface: 'Interfaz',
  abstract: 'Abstracta',
  enum: 'Enumeración',
}

const NONE = '__none__'

/**
 * Lista de tipos para un atributo. Un valor que ya está guardado pero no figura en la lista (por ejemplo
 * `List<String>`, que puede venir del Copilot) se conserva como opción para no perderlo al mostrarlo.
 */
function AttributeTypeSelect({
  attributeName,
  value,
  classNames,
  disabled,
  onChange,
}: {
  attributeName: string
  value: string
  classNames: string[]
  disabled: boolean
  onChange: (type: string) => void
}) {
  const known = (DATA_TYPES as readonly string[]).includes(value) || classNames.includes(value)
  return (
    <Select value={value} disabled={disabled} onValueChange={(type) => type !== value && onChange(type)}>
      <SelectTrigger aria-label={`Tipo del atributo ${attributeName}`} className="w-36 shrink-0">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectGroup>
          <SelectLabel>Tipos básicos</SelectLabel>
          {DATA_TYPES.map((type) => (
            <SelectItem key={type} value={type}>
              {type}
            </SelectItem>
          ))}
        </SelectGroup>
        {classNames.length > 0 && (
          <SelectGroup>
            <SelectLabel>Clases del diagrama</SelectLabel>
            {classNames.map((name) => (
              <SelectItem key={name} value={name}>
                {name}
              </SelectItem>
            ))}
          </SelectGroup>
        )}
        {!known && (
          <SelectGroup>
            <SelectLabel>Actual</SelectLabel>
            <SelectItem value={value}>{value}</SelectItem>
          </SelectGroup>
        )}
      </SelectContent>
    </Select>
  )
}

/** CU-08 Gestionar clases, atributos y métodos. Cada cambio viaja como una operación. */
export function ClassPropertiesPanel({ uml, readOnly, send, otherClassNames = [] }: Props) {
  const id = useId()

  const updateClass = (changes: { name?: string; stereotype?: Stereotype | null; visibility?: Visibility }) =>
    send({ op: 'UPDATE_CLASS', classId: uml.id, changes })

  const updateMethod = (method: Method, changes: Partial<Omit<Method, 'id'>>) =>
    send({ op: 'UPDATE_METHOD', classId: uml.id, methodId: method.id, changes })

  return (
    <div className="grid gap-4">
      <div className="grid gap-2">
        <Label htmlFor={`${id}-name`}>Nombre de la clase</Label>
        <Input
          id={`${id}-name`}
          key={uml.name}
          defaultValue={uml.name}
          disabled={readOnly}
          onBlur={(e) => e.target.value.trim() !== uml.name && updateClass({ name: e.target.value.trim() })}
        />
      </div>

      <div className="grid grid-cols-2 gap-3">
        <div className="grid gap-2">
          <Label htmlFor={`${id}-stereotype`}>Estereotipo</Label>
          <Select
            value={uml.stereotype ?? NONE}
            disabled={readOnly}
            onValueChange={(value) => updateClass({ stereotype: value === NONE ? null : (value as Stereotype) })}
          >
            <SelectTrigger id={`${id}-stereotype`}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={NONE}>Ninguno</SelectItem>
              {STEREOTYPES.map((stereotype) => (
                <SelectItem key={stereotype} value={stereotype}>
                  {STEREOTYPE_LABEL[stereotype]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="grid gap-2">
          <Label htmlFor={`${id}-visibility`}>Visibilidad</Label>
          <Select
            value={uml.visibility}
            disabled={readOnly}
            onValueChange={(value) => updateClass({ visibility: value as Visibility })}
          >
            <SelectTrigger id={`${id}-visibility`}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {VISIBILITIES.map((visibility) => (
                <SelectItem key={visibility} value={visibility}>
                  {VISIBILITY_LABEL[visibility]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>

      <Separator />

      <section className="grid gap-2">
        <header className="flex items-center justify-between">
          <h3 className="text-sm font-medium">Atributos</h3>
          <Button
            size="sm"
            variant="outline"
            disabled={readOnly}
            onClick={() =>
              send({
                op: 'ADD_ATTRIBUTE',
                classId: uml.id,
                attribute: { name: `atributo${uml.attributes.length + 1}`, type: 'String', visibility: 'PRIVATE' },
              })
            }
          >
            <Plus className="size-4" aria-hidden />
            Agregar
          </Button>
        </header>

        {uml.attributes.length === 0 && <p className="text-xs text-muted-foreground">Sin atributos.</p>}

        {uml.attributes.map((attribute) => (
          <div key={attribute.id} className="flex items-center gap-2">
            <Input
              aria-label={`Nombre del atributo ${attribute.name}`}
              key={`${attribute.id}-name-${attribute.name}`}
              defaultValue={attribute.name}
              disabled={readOnly}
              onBlur={(e) =>
                e.target.value.trim() !== attribute.name &&
                send({
                  op: 'UPDATE_ATTRIBUTE',
                  classId: uml.id,
                  attributeId: attribute.id,
                  changes: { name: e.target.value.trim() },
                })
              }
            />
            <AttributeTypeSelect
              attributeName={attribute.name}
              value={attribute.type}
              classNames={otherClassNames}
              disabled={readOnly}
              onChange={(type) =>
                send({ op: 'UPDATE_ATTRIBUTE', classId: uml.id, attributeId: attribute.id, changes: { type } })
              }
            />
            <Button
              size="icon"
              variant="ghost"
              aria-label={`Eliminar el atributo ${attribute.name}`}
              disabled={readOnly}
              onClick={() =>
                send({ op: 'REMOVE_ATTRIBUTE', classId: uml.id, attributeId: attribute.id })
              }
            >
              <Trash2 className="size-4" aria-hidden />
            </Button>
          </div>
        ))}
      </section>

      <Separator />

      <section className="grid gap-2">
        <header className="flex items-center justify-between">
          <h3 className="text-sm font-medium">Métodos</h3>
          <Button
            size="sm"
            variant="outline"
            disabled={readOnly}
            onClick={() =>
              send({
                op: 'ADD_METHOD',
                classId: uml.id,
                method: {
                  name: `metodo${uml.methods.length + 1}`,
                  returnType: 'void',
                  visibility: 'PUBLIC',
                  parameters: [],
                },
              })
            }
          >
            <Plus className="size-4" aria-hidden />
            Agregar
          </Button>
        </header>

        {uml.methods.length === 0 && <p className="text-xs text-muted-foreground">Sin métodos.</p>}

        {uml.methods.map((method) => (
          <div key={method.id} className="grid gap-2 rounded-md border p-2">
            <div className="flex items-center gap-2">
              <Input
                aria-label={`Nombre del método ${method.name}`}
                key={`${method.id}-name-${method.name}`}
                defaultValue={method.name}
                disabled={readOnly}
                onBlur={(e) => e.target.value.trim() !== method.name && updateMethod(method, { name: e.target.value.trim() })}
              />
              <Input
                aria-label={`Tipo de retorno de ${method.name}`}
                key={`${method.id}-return-${method.returnType}`}
                defaultValue={method.returnType}
                disabled={readOnly}
                className="w-32"
                onBlur={(e) =>
                  e.target.value.trim() !== method.returnType &&
                  updateMethod(method, { returnType: e.target.value.trim() })
                }
              />
              <Button
                size="icon"
                variant="ghost"
                aria-label={`Eliminar el método ${method.name}`}
                disabled={readOnly}
                onClick={() => send({ op: 'REMOVE_METHOD', classId: uml.id, methodId: method.id })}
              >
                <Trash2 className="size-4" aria-hidden />
              </Button>
            </div>
            <Input
              aria-label={`Parámetros de ${method.name}`}
              key={`${method.id}-params-${formatParameters(method.parameters)}`}
              defaultValue={formatParameters(method.parameters)}
              placeholder="nombre: Tipo, otro: Tipo"
              disabled={readOnly}
              onBlur={(e) => {
                const parameters = parseParameters(e.target.value)
                if (formatParameters(parameters) !== formatParameters(method.parameters)) {
                  updateMethod(method, { parameters })
                }
              }}
            />
          </div>
        ))}
      </section>

      <Separator />

      <ConfirmButton
        title={`¿Eliminar la clase ${uml.name}?`}
        description="Se eliminarán también las relaciones que la conectan con otras clases. No se puede deshacer."
        confirmLabel="Eliminar la clase"
        disabled={readOnly}
        onConfirm={() => send({ op: 'REMOVE_CLASS', classId: uml.id })}
      >
        <Trash2 className="size-4" aria-hidden />
        Eliminar la clase
      </ConfirmButton>
    </div>
  )
}
