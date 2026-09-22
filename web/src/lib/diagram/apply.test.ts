import { applyOperation } from './apply'
import type { ClassContent } from './types'

const base: ClassContent = {
  schemaVersion: 1,
  type: 'CLASS',
  classes: [
    {
      id: 'c1',
      name: 'Cliente',
      stereotype: null,
      visibility: 'PUBLIC',
      x: 100,
      y: 80,
      attributes: [{ id: 'a1', name: 'nombre', type: 'String', visibility: 'PRIVATE' }],
      methods: [{ id: 'm1', name: 'getNombre', returnType: 'String', visibility: 'PUBLIC', parameters: [] }],
    },
    {
      id: 'c2',
      name: 'Pedido',
      stereotype: null,
      visibility: 'PUBLIC',
      x: 400,
      y: 80,
      attributes: [],
      methods: [],
    },
  ],
  relationships: [
    { id: 'r1', type: 'ASSOCIATION', sourceId: 'c1', targetId: 'c2', sourceMultiplicity: '1', targetMultiplicity: '0..*' },
  ],
}

describe('aplicación local de una operación ya validada', () => {
  it('no modifica el estado anterior', () => {
    const next = applyOperation(base, { op: 'MOVE_CLASS', classId: 'c1', x: 500, y: 500 })

    expect(base.classes[0].x).toBe(100)
    expect(next).not.toBe(base)
  })

  it('MOVE_CLASS cambia la posición', () => {
    const next = applyOperation(base, { op: 'MOVE_CLASS', classId: 'c1', x: 300, y: 220 })
    expect(next.classes[0]).toMatchObject({ x: 300, y: 220 })
  })

  it('ADD_CLASS agrega la clase que devolvió el servidor, con su id', () => {
    const next = applyOperation(base, {
      op: 'ADD_CLASS',
      class: {
        id: 'c3',
        name: 'Factura',
        stereotype: null,
        visibility: 'PUBLIC',
        x: 0,
        y: 0,
        attributes: [],
        methods: [],
      } as never,
    })
    expect(next.classes.map((c) => c.id)).toEqual(['c1', 'c2', 'c3'])
  })

  it('REMOVE_CLASS arrastra las relaciones que tocaban esa clase', () => {
    const next = applyOperation(base, { op: 'REMOVE_CLASS', classId: 'c2' })

    expect(next.classes.map((c) => c.id)).toEqual(['c1'])
    expect(next.relationships).toHaveLength(0)
  })

  it('UPDATE_CLASS aplica solo los campos enviados', () => {
    const next = applyOperation(base, { op: 'UPDATE_CLASS', classId: 'c1', changes: { name: 'Persona' } })

    expect(next.classes[0].name).toBe('Persona')
    expect(next.classes[0].visibility).toBe('PUBLIC')
  })

  it('agrega, modifica y quita atributos', () => {
    const added = applyOperation(base, {
      op: 'ADD_ATTRIBUTE',
      classId: 'c1',
      attribute: { id: 'a2', name: 'email', type: 'String', visibility: 'PRIVATE' } as never,
    })
    expect(added.classes[0].attributes).toHaveLength(2)

    const updated = applyOperation(added, {
      op: 'UPDATE_ATTRIBUTE',
      classId: 'c1',
      attributeId: 'a2',
      changes: { type: 'Integer' },
    })
    expect(updated.classes[0].attributes[1].type).toBe('Integer')

    const removed = applyOperation(updated, { op: 'REMOVE_ATTRIBUTE', classId: 'c1', attributeId: 'a1' })
    expect(removed.classes[0].attributes.map((a) => a.id)).toEqual(['a2'])
  })

  it('agrega, modifica y quita métodos', () => {
    const added = applyOperation(base, {
      op: 'ADD_METHOD',
      classId: 'c2',
      method: { id: 'm2', name: 'total', returnType: 'BigDecimal', visibility: 'PUBLIC', parameters: [] } as never,
    })
    expect(added.classes[1].methods).toHaveLength(1)

    const updated = applyOperation(added, {
      op: 'UPDATE_METHOD',
      classId: 'c2',
      methodId: 'm2',
      changes: { parameters: [{ name: 'iva', type: 'double' }] },
    })
    expect(updated.classes[1].methods[0].parameters).toEqual([{ name: 'iva', type: 'double' }])

    const removed = applyOperation(updated, { op: 'REMOVE_METHOD', classId: 'c2', methodId: 'm2' })
    expect(removed.classes[1].methods).toHaveLength(0)
  })

  it('agrega, modifica y quita relaciones', () => {
    const added = applyOperation(base, {
      op: 'ADD_RELATIONSHIP',
      relationship: { id: 'r2', type: 'GENERALIZATION', sourceId: 'c2', targetId: 'c1' } as never,
    })
    expect(added.relationships).toHaveLength(2)

    const updated = applyOperation(added, {
      op: 'UPDATE_RELATIONSHIP',
      relationshipId: 'r2',
      changes: { type: 'REALIZATION' },
    })
    expect(updated.relationships[1].type).toBe('REALIZATION')

    const removed = applyOperation(updated, { op: 'REMOVE_RELATIONSHIP', relationshipId: 'r1' })
    expect(removed.relationships.map((r) => r.id)).toEqual(['r2'])
  })
})

describe('valores por omisión que el servidor no difunde', () => {
  it('ADD_CLASS sin visibilidad, estereotipo ni métodos queda igual que en el servidor', () => {
    // Así llega realmente el mensaje OP: con ids asignados, pero sin los valores por omisión.
    const next = applyOperation(base, {
      op: 'ADD_CLASS',
      class: {
        id: 'c3',
        name: 'Factura',
        x: 120,
        y: 80,
        attributes: [{ id: 'a9', name: 'total', type: 'BigDecimal' }],
      } as never,
    })

    expect(next.classes[2]).toEqual({
      id: 'c3',
      name: 'Factura',
      stereotype: null,
      visibility: 'PUBLIC',
      x: 120,
      y: 80,
      attributes: [{ id: 'a9', name: 'total', type: 'BigDecimal', visibility: 'PRIVATE' }],
      methods: [],
    })
  })

  it('ADD_ATTRIBUTE sin visibilidad se guarda como privado', () => {
    const next = applyOperation(base, {
      op: 'ADD_ATTRIBUTE',
      classId: 'c1',
      attribute: { id: 'a2', name: 'email', type: 'String' } as never,
    })
    expect(next.classes[0].attributes[1].visibility).toBe('PRIVATE')
  })

  it('ADD_METHOD sin retorno ni visibilidad usa void y público', () => {
    const next = applyOperation(base, {
      op: 'ADD_METHOD',
      classId: 'c2',
      method: { id: 'm9', name: 'anular' } as never,
    })
    expect(next.classes[1].methods[0]).toMatchObject({ returnType: 'void', visibility: 'PUBLIC', parameters: [] })
  })
})
