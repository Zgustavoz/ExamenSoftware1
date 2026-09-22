import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { DiagramOperation } from '@/lib/diagram/operations'
import { DATA_TYPES, type UmlClass } from '@/lib/diagram/types'
import { ClassPropertiesPanel } from './ClassPropertiesPanel'

const cliente: UmlClass = {
  id: 'c1',
  name: 'Cliente',
  stereotype: null,
  visibility: 'PUBLIC',
  x: 100,
  y: 80,
  attributes: [{ id: 'a1', name: 'nombre', type: 'String', visibility: 'PRIVATE' }],
  methods: [{ id: 'm1', name: 'getNombre', returnType: 'String', visibility: 'PUBLIC', parameters: [] }],
}

/** Monta el panel y devuelve las operaciones que habría enviado. */
function renderPanel(uml: UmlClass = cliente, readOnly = false, otherClassNames: string[] = []) {
  const sent: DiagramOperation[] = []
  render(
    <ClassPropertiesPanel
      uml={uml}
      readOnly={readOnly}
      otherClassNames={otherClassNames}
      send={(operation) => {
        sent.push(operation)
        return true
      }}
    />,
  )
  return { sent, user: userEvent.setup() }
}

describe('CU-08 Gestionar clases, atributos y métodos', () => {
  it('renombrar la clase envía UPDATE_CLASS al salir del campo', async () => {
    const { sent, user } = renderPanel()

    const name = screen.getByLabelText('Nombre de la clase')
    await user.clear(name)
    await user.type(name, 'Persona')
    await user.tab()

    expect(sent).toEqual([{ op: 'UPDATE_CLASS', classId: 'c1', changes: { name: 'Persona' } }])
  })

  it('no envía nada si el nombre no cambió', async () => {
    const { sent, user } = renderPanel()

    await user.click(screen.getByLabelText('Nombre de la clase'))
    await user.tab()

    expect(sent).toHaveLength(0)
  })

  it('cambiar el estereotipo a interfaz envía UPDATE_CLASS', async () => {
    const { sent, user } = renderPanel()

    await user.click(screen.getByRole('combobox', { name: 'Estereotipo' }))
    await user.click(await screen.findByRole('option', { name: 'Interfaz' }))

    expect(sent).toEqual([{ op: 'UPDATE_CLASS', classId: 'c1', changes: { stereotype: 'interface' } }])
  })

  it('quitar el estereotipo lo envía como nulo', async () => {
    const { sent, user } = renderPanel({ ...cliente, stereotype: 'interface' })

    await user.click(screen.getByRole('combobox', { name: 'Estereotipo' }))
    await user.click(await screen.findByRole('option', { name: 'Ninguno' }))

    expect(sent).toEqual([{ op: 'UPDATE_CLASS', classId: 'c1', changes: { stereotype: null } }])
  })

  it('agrega un atributo con la visibilidad privada por omisión', async () => {
    const { sent, user } = renderPanel()

    await user.click(screen.getAllByRole('button', { name: 'Agregar' })[0])

    expect(sent).toEqual([
      {
        op: 'ADD_ATTRIBUTE',
        classId: 'c1',
        attribute: { name: 'atributo2', type: 'String', visibility: 'PRIVATE' },
      },
    ])
  })

  it('el tipo de un atributo se elige de una lista, no se escribe', async () => {
    const { sent, user } = renderPanel()

    await user.click(screen.getByRole('combobox', { name: 'Tipo del atributo nombre' }))
    await user.click(await screen.findByRole('option', { name: 'int' }))

    expect(sent).toEqual([{ op: 'UPDATE_ATTRIBUTE', classId: 'c1', attributeId: 'a1', changes: { type: 'int' } }])
  })

  it('la lista ofrece todos los tipos que acepta el servidor', async () => {
    const { user } = renderPanel()

    await user.click(screen.getByRole('combobox', { name: 'Tipo del atributo nombre' }))

    for (const type of DATA_TYPES) {
      expect(await screen.findByRole('option', { name: type })).toBeInTheDocument()
    }
  })

  it('las demás clases del diagrama también se pueden elegir como tipo', async () => {
    const { sent, user } = renderPanel(cliente, false, ['Pedido'])

    await user.click(screen.getByRole('combobox', { name: 'Tipo del atributo nombre' }))
    await user.click(await screen.findByRole('option', { name: 'Pedido' }))

    expect(sent).toEqual([{ op: 'UPDATE_ATTRIBUTE', classId: 'c1', attributeId: 'a1', changes: { type: 'Pedido' } }])
  })

  it('un tipo guardado que no está en la lista se conserva y se muestra', async () => {
    const { user } = renderPanel({
      ...cliente,
      attributes: [{ id: 'a1', name: 'etiquetas', type: 'List<String>', visibility: 'PRIVATE' }],
    })

    expect(screen.getByRole('combobox', { name: 'Tipo del atributo etiquetas' })).toHaveTextContent('List<String>')
    await user.click(screen.getByRole('combobox', { name: 'Tipo del atributo etiquetas' }))
    expect(await screen.findByRole('option', { name: 'List<String>' })).toBeInTheDocument()
  })

  it('elegir el mismo tipo que ya tiene no envía nada', async () => {
    const { sent, user } = renderPanel()

    await user.click(screen.getByRole('combobox', { name: 'Tipo del atributo nombre' }))
    await user.click(await screen.findByRole('option', { name: 'String' }))

    expect(sent).toHaveLength(0)
  })

  it('elimina un atributo', async () => {
    const { sent, user } = renderPanel()

    await user.click(screen.getByRole('button', { name: 'Eliminar el atributo nombre' }))

    expect(sent).toEqual([{ op: 'REMOVE_ATTRIBUTE', classId: 'c1', attributeId: 'a1' }])
  })

  it('agrega un método público que devuelve void', async () => {
    const { sent, user } = renderPanel()

    await user.click(screen.getAllByRole('button', { name: 'Agregar' })[1])

    expect(sent).toEqual([
      {
        op: 'ADD_METHOD',
        classId: 'c1',
        method: { name: 'metodo2', returnType: 'void', visibility: 'PUBLIC', parameters: [] },
      },
    ])
  })

  it('los parámetros se escriben como texto y viajan como lista', async () => {
    const { sent, user } = renderPanel()

    const params = screen.getByLabelText('Parámetros de getNombre')
    await user.type(params, 'idioma: String, corto: boolean')
    await user.tab()

    expect(sent).toEqual([
      {
        op: 'UPDATE_METHOD',
        classId: 'c1',
        methodId: 'm1',
        changes: {
          parameters: [
            { name: 'idioma', type: 'String' },
            { name: 'corto', type: 'boolean' },
          ],
        },
      },
    ])
  })

  it('elimina un método', async () => {
    const { sent, user } = renderPanel()

    await user.click(screen.getByRole('button', { name: 'Eliminar el método getNombre' }))

    expect(sent).toEqual([{ op: 'REMOVE_METHOD', classId: 'c1', methodId: 'm1' }])
  })

  it('eliminar la clase pide confirmación antes de enviarla', async () => {
    const { sent, user } = renderPanel()

    await user.click(screen.getByRole('button', { name: 'Eliminar la clase' }))
    expect(await screen.findByRole('alertdialog')).toHaveTextContent(/¿Eliminar la clase Cliente\?/)
    expect(sent).toHaveLength(0)

    await user.click(screen.getByRole('button', { name: 'Eliminar la clase' }))
    expect(sent).toEqual([{ op: 'REMOVE_CLASS', classId: 'c1' }])
  })

  it('cancelar la confirmación no elimina nada', async () => {
    const { sent, user } = renderPanel()

    await user.click(screen.getByRole('button', { name: 'Eliminar la clase' }))
    await user.click(await screen.findByRole('button', { name: 'Cancelar' }))

    expect(sent).toHaveLength(0)
  })

  it('en modo lectura todo queda deshabilitado', () => {
    renderPanel(cliente, true)

    expect(screen.getByLabelText('Nombre de la clase')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Eliminar la clase' })).toBeDisabled()
    expect(screen.getAllByRole('button', { name: 'Agregar' })[0]).toBeDisabled()
  })
})
