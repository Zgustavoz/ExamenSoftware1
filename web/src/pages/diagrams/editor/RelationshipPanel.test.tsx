import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { DiagramOperation } from '@/lib/diagram/operations'
import type { Relationship, UmlClass } from '@/lib/diagram/types'
import { NewRelationshipDialog } from './NewRelationshipDialog'
import { RelationshipPanel } from './RelationshipPanel'

const classes: UmlClass[] = [
  { id: 'c1', name: 'Cliente', stereotype: null, visibility: 'PUBLIC', x: 0, y: 0, attributes: [], methods: [] },
  { id: 'c2', name: 'Pedido', stereotype: null, visibility: 'PUBLIC', x: 300, y: 0, attributes: [], methods: [] },
]

const relationship: Relationship = {
  id: 'r1',
  type: 'ASSOCIATION',
  sourceId: 'c1',
  targetId: 'c2',
  sourceMultiplicity: '1',
  targetMultiplicity: '0..*',
}

function renderPanel(readOnly = false) {
  const sent: DiagramOperation[] = []
  render(
    <RelationshipPanel
      relationship={relationship}
      classes={classes}
      readOnly={readOnly}
      send={(operation) => {
        sent.push(operation)
        return true
      }}
    />,
  )
  return { sent, user: userEvent.setup() }
}

describe('CU-09 Gestionar relaciones', () => {
  it('muestra los extremos de la relación', () => {
    renderPanel()
    expect(screen.getByText('Cliente → Pedido')).toBeInTheDocument()
  })

  it('cambiar el tipo a composición envía UPDATE_RELATIONSHIP', async () => {
    const { sent, user } = renderPanel()

    await user.click(screen.getByRole('combobox', { name: 'Tipo' }))
    await user.click(await screen.findByRole('option', { name: 'Composición' }))

    expect(sent).toEqual([{ op: 'UPDATE_RELATIONSHIP', relationshipId: 'r1', changes: { type: 'COMPOSITION' } }])
  })

  it.each(['0..1', '*', '3', '1..*'])('acepta la multiplicidad «%s»', async (value) => {
    const { sent, user } = renderPanel()

    const field = screen.getByLabelText('Multiplicidad de destino')
    await user.clear(field)
    await user.type(field, value)
    await user.tab()

    expect(sent).toEqual([
      { op: 'UPDATE_RELATIONSHIP', relationshipId: 'r1', changes: { targetMultiplicity: value } },
    ])
  })

  it.each(['muchos', '1..', 'a..b'])('no envía la multiplicidad inválida «%s»', async (value) => {
    const { sent, user } = renderPanel()

    const field = screen.getByLabelText('Multiplicidad de destino')
    await user.clear(field)
    await user.type(field, value)
    await user.tab()

    expect(sent).toHaveLength(0)
  })

  it('un rol vacío viaja como nulo', async () => {
    const { sent, user } = renderPanel()

    const field = screen.getByLabelText('Rol de origen')
    await user.type(field, '   ')
    await user.tab()

    expect(sent).toEqual([{ op: 'UPDATE_RELATIONSHIP', relationshipId: 'r1', changes: { sourceRole: null } }])
  })

  it('eliminar la relación pide confirmación antes de enviarla', async () => {
    const { sent, user } = renderPanel()

    await user.click(screen.getByRole('button', { name: 'Eliminar la relación' }))
    expect(await screen.findByRole('alertdialog')).toHaveTextContent(/Cliente y Pedido/)
    expect(sent).toHaveLength(0)

    await user.click(screen.getByRole('button', { name: 'Eliminar la relación' }))
    expect(sent).toEqual([{ op: 'REMOVE_RELATIONSHIP', relationshipId: 'r1' }])
  })

  it('en modo lectura no se puede eliminar', () => {
    renderPanel(true)
    expect(screen.getByRole('button', { name: 'Eliminar la relación' })).toBeDisabled()
  })
})

describe('CU-09 Nueva relación al conectar dos clases', () => {
  function renderDialog() {
    const created: unknown[] = []
    render(
      <NewRelationshipDialog
        sourceId="c1"
        targetId="c2"
        classes={classes}
        onCancel={() => {}}
        onCreate={(relationship) => created.push(relationship)}
      />,
    )
    return { created, user: userEvent.setup() }
  }

  it('crea una asociación con las multiplicidades propuestas', async () => {
    const { created, user } = renderDialog()

    await user.click(screen.getByRole('button', { name: 'Crear relación' }))

    expect(created).toEqual([
      {
        type: 'ASSOCIATION',
        sourceId: 'c1',
        targetId: 'c2',
        sourceMultiplicity: '1',
        targetMultiplicity: '0..*',
      },
    ])
  })

  it('la herencia no pide multiplicidades', async () => {
    const { created, user } = renderDialog()

    await user.click(screen.getByRole('combobox', { name: 'Tipo' }))
    await user.click(await screen.findByRole('option', { name: 'Herencia' }))

    expect(screen.queryByLabelText('Multiplicidad de destino')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Crear relación' }))
    expect(created).toEqual([
      {
        type: 'GENERALIZATION',
        sourceId: 'c1',
        targetId: 'c2',
        sourceMultiplicity: null,
        targetMultiplicity: null,
      },
    ])
  })

  it('no deja crear con una multiplicidad mal escrita', async () => {
    const { user } = renderDialog()

    const field = screen.getByLabelText('Multiplicidad de destino')
    await user.clear(field)
    await user.type(field, 'varios')

    expect(screen.getByRole('button', { name: 'Crear relación' })).toBeDisabled()
    expect(screen.getByText(/Use un formato como/)).toBeInTheDocument()
  })
})
