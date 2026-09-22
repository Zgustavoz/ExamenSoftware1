import { contentToGraph, withSelectionPreserved } from './DiagramCanvas'
import type { ClassContent } from '@/lib/diagram/types'

const content: ClassContent = {
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
      attributes: [],
      methods: [],
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
  relationships: [{ id: 'r1', type: 'ASSOCIATION', sourceId: 'c1', targetId: 'c2' }],
}

describe('conversión de content_json al grafo', () => {
  it('usa los ids del servidor y su posición', () => {
    const { nodes, edges } = contentToGraph(content)

    expect(nodes.map((n) => n.id)).toEqual(['c1', 'c2'])
    expect(nodes[0].position).toEqual({ x: 100, y: 80 })
    expect(edges[0]).toMatchObject({ id: 'r1', source: 'c1', target: 'c2' })
  })

  it('una clase bloqueada por otro usuario no se puede arrastrar', () => {
    const { nodes } = contentToGraph(content, { c1: 'otra' })

    expect(nodes[0].draggable).toBe(false)
    expect(nodes[0].data.lockedBy).toBe('otra')
    expect(nodes[1].draggable).toBe(true)
  })
})

interface Element {
  id: string
  selected?: boolean
}

describe('la selección sobrevive a una operación difundida', () => {
  it('conserva marcado lo que el usuario tenía seleccionado', () => {
    const previous: Element[] = [
      { id: 'c1', selected: true },
      { id: 'c2', selected: false },
    ]
    const next: Element[] = [{ id: 'c1' }, { id: 'c2' }, { id: 'c3' }]

    expect(withSelectionPreserved(previous, next)).toEqual([
      { id: 'c1', selected: true },
      { id: 'c2' },
      { id: 'c3' },
    ])
  })

  it('no resucita la selección de un elemento que ya no existe', () => {
    const previous: Element[] = [{ id: 'c9', selected: true }]

    expect(withSelectionPreserved(previous, [{ id: 'c1' }])).toEqual([{ id: 'c1' }])
  })

  it('sin nada seleccionado devuelve el grafo tal cual', () => {
    const next: Element[] = [{ id: 'c1' }, { id: 'c2' }]

    expect(withSelectionPreserved([], next)).toEqual(next)
  })
})
