import { screen } from '@testing-library/react'
import { toMermaid } from '@/components/diagram/SequenceView'
import { getDiagram, type Diagram } from '@/lib/api/diagrams'
import type { Role, User } from '@/lib/api/types'
import type { ClassContent, SequenceContent } from '@/lib/diagram/types'
import { useAuthStore } from '@/stores/auth-store'
import { renderApp } from '@/test/render'

vi.mock('@/lib/api/diagrams', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/diagrams')>()),
  getDiagram: vi.fn(),
}))
const get = vi.mocked(getDiagram)

const classContent: ClassContent = {
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
  ],
  relationships: [],
}

const sequenceContent: SequenceContent = {
  schemaVersion: 1,
  type: 'SEQUENCE',
  lifelines: [
    { id: 'l1', name: 'Cliente', classId: 'c1' },
    { id: 'l2', name: 'Pedido', classId: 'c2' },
  ],
  messages: [
    { id: 's2', order: 2, fromId: 'l2', toId: 'l1', name: 'confirmacion', kind: 'RETURN' },
    { id: 's1', order: 1, fromId: 'l1', toId: 'l2', name: 'crearPedido', kind: 'SYNC' },
  ],
}

function diagram(overrides: Partial<Diagram> = {}): Diagram {
  return {
    id: 'd1',
    projectId: 'p1',
    name: 'Dominio',
    description: 'Modelo del dominio',
    type: 'CLASS',
    contentJson: classContent,
    version: 4,
    sourceDiagramId: null,
    createdBy: 'u1',
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-05T10:00:00Z',
    ...overrides,
  }
}

function signIn(...roles: Role[]) {
  useAuthStore.getState().login('jwt', {
    id: 'u1',
    username: 'alguien',
    email: 'a@demo.com',
    fullName: 'Ana',
    roles,
    companyId: 'c1',
    active: true,
    createdAt: '2026-01-01T00:00:00Z',
  } satisfies User)
}

describe('CU-11 Consultar diagrama', () => {
  afterEach(() => {
    useAuthStore.getState().logout()
    vi.resetAllMocks()
  })

  it('muestra el nombre, el tipo y la versión del diagrama', async () => {
    signIn('DEVELOPER')
    get.mockResolvedValue(diagram())
    renderApp('/diagrams/d1/view')

    expect(await screen.findByRole('heading', { name: /Dominio/ })).toBeInTheDocument()
    expect(screen.getByText('Clases')).toBeInTheDocument()
    expect(screen.getByText('versión 4')).toBeInTheDocument()
  })

  it('dibuja las clases con sus atributos y métodos', async () => {
    signIn('DEVELOPER')
    get.mockResolvedValue(diagram())
    renderApp('/diagrams/d1/view')

    expect(await screen.findByText('Cliente')).toBeInTheDocument()
    expect(screen.getByText(/nombre: String/)).toBeInTheDocument()
    expect(screen.getByText(/getNombre\(\): String/)).toBeInTheDocument()
  })

  it('un diagrama inexistente o de otra empresa da el mismo aviso', async () => {
    signIn('DESIGNER')
    get.mockResolvedValue(null)
    renderApp('/diagrams/zzz/view')

    expect(await screen.findByText(/no existe o no tiene acceso/)).toBeInTheDocument()
  })

  it('el DESIGNER puede pasar al editor; el DEVELOPER no', async () => {
    signIn('DESIGNER')
    get.mockResolvedValue(diagram())
    const { unmount } = renderApp('/diagrams/d1/view')
    expect(await screen.findByRole('link', { name: 'Editar' })).toHaveAttribute('href', '/diagrams/d1')
    unmount()

    useAuthStore.getState().logout()
    signIn('DEVELOPER')
    renderApp('/diagrams/d1/view')
    await screen.findByRole('heading', { name: /Dominio/ })
    expect(screen.queryByRole('link', { name: 'Editar' })).not.toBeInTheDocument()
  })

  it('un diagrama de secuencia no ofrece editar', async () => {
    signIn('DESIGNER')
    get.mockResolvedValue(diagram({ type: 'SEQUENCE', contentJson: sequenceContent }))
    renderApp('/diagrams/d1/view')

    await screen.findByRole('heading', { name: /Dominio/ })
    expect(screen.getByText('Secuencia')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Editar' })).not.toBeInTheDocument()
  })

  it('avisa si el diagrama de clases está vacío', async () => {
    signIn('DESIGNER')
    get.mockResolvedValue(
      diagram({ contentJson: { schemaVersion: 1, type: 'CLASS', classes: [], relationships: [] } }),
    )
    renderApp('/diagrams/d1/view')

    expect(await screen.findByText('El diagrama está vacío.')).toBeInTheDocument()
  })
})

describe('traducción a Mermaid', () => {
  it('declara las líneas de vida y ordena los mensajes por «order»', () => {
    const mermaid = toMermaid(sequenceContent)

    expect(mermaid).toContain('sequenceDiagram')
    expect(mermaid).toContain('participant L0 as Cliente')
    expect(mermaid).toContain('participant L1 as Pedido')
    expect(mermaid.indexOf('crearPedido')).toBeLessThan(mermaid.indexOf('confirmacion'))
  })

  it('una línea de vida sin clase (el actor que inicia el flujo) se dibuja como muñeco', () => {
    const mermaid = toMermaid({
      ...sequenceContent,
      lifelines: [{ id: 'l0', name: 'Usuario', classId: null }, ...sequenceContent.lifelines],
    })

    expect(mermaid).toContain('actor L0 as Usuario')
    // Las que sí son una clase del diagrama siguen siendo cajas, sin estereotipo.
    expect(mermaid).toContain('participant L1 as Cliente')
    expect(mermaid).toContain('participant L2 as Pedido')
    expect(mermaid).not.toContain('<<')
  })

  it('una línea de vida sin «classId» tampoco es una clase: es un actor', () => {
    const mermaid = toMermaid({ ...sequenceContent, lifelines: [{ id: 'l1', name: 'Cliente' }] })

    expect(mermaid).toContain('actor L0 as Cliente')
  })

  it('usa la flecha propia de cada tipo de mensaje', () => {
    const mermaid = toMermaid(sequenceContent)

    expect(mermaid).toContain('L0->>L1: crearPedido')
    expect(mermaid).toContain('L1-->>L0: confirmacion')
  })

  it('un mensaje a uno mismo vuelve a su propia línea de vida', () => {
    const mermaid = toMermaid({
      ...sequenceContent,
      messages: [{ id: 's1', order: 1, fromId: 'l1', toId: 'l2', name: 'validar', kind: 'SELF' }],
    })

    expect(mermaid).toContain('L0->>L0: validar')
  })
})
