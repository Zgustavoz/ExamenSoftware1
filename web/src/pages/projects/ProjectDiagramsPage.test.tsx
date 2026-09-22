import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createDiagram, listDiagrams, type Diagram } from '@/lib/api/diagrams'
import { ApiError } from '@/lib/api/errors'
import { getProject, type Project } from '@/lib/api/projects'
import type { Role, User } from '@/lib/api/types'
import { EMPTY_CLASS_CONTENT } from '@/lib/diagram/types'
import { useAuthStore } from '@/stores/auth-store'
import { renderApp } from '@/test/render'

vi.mock('@/lib/api/diagrams', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/diagrams')>()),
  listDiagrams: vi.fn(),
  createDiagram: vi.fn(),
}))
vi.mock('@/lib/api/projects', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/projects')>()),
  getProject: vi.fn(),
}))

const list = vi.mocked(listDiagrams)
const create = vi.mocked(createDiagram)
const project = vi.mocked(getProject)

const ventas: Project = {
  id: 'p1',
  name: 'Ventas',
  description: 'Sistema de ventas',
  ownerId: 'u1',
  ownerName: 'Ana',
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

function diagram(id: string, name: string, type: 'CLASS' | 'SEQUENCE' = 'CLASS'): Diagram {
  return {
    id,
    projectId: 'p1',
    name,
    description: null,
    type,
    contentJson: EMPTY_CLASS_CONTENT,
    version: 3,
    sourceDiagramId: null,
    createdBy: 'u1',
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-05T10:00:00Z',
  }
}

function signIn(...roles: Role[]) {
  useAuthStore.getState().login('jwt', {
    id: 'u1',
    username: 'alguien',
    email: 'a@demo.com',
    fullName: 'Ana Pérez',
    roles,
    companyId: 'c1',
    active: true,
    createdAt: '2026-01-01T00:00:00Z',
  } satisfies User)
}

describe('CU-06 Crear diagrama de clases', () => {
  beforeEach(() => project.mockResolvedValue(ventas))
  afterEach(() => {
    useAuthStore.getState().logout()
    vi.resetAllMocks()
  })

  it('lista los diagramas del proyecto con su tipo y versión', async () => {
    signIn('DESIGNER')
    list.mockResolvedValue([diagram('d1', 'Dominio'), diagram('d2', 'Flujo de compra', 'SEQUENCE')])
    renderApp('/projects/p1')

    expect(await screen.findByRole('link', { name: 'Dominio' })).toHaveAttribute('href', '/diagrams/d1')
    expect(screen.getByText('Clases')).toBeInTheDocument()
    expect(screen.getByText('Secuencia')).toBeInTheDocument()
    expect(screen.getAllByText('versión 3')).toHaveLength(2)
  })

  it('el diagrama de secuencia siempre lleva al visor', async () => {
    signIn('DESIGNER')
    list.mockResolvedValue([diagram('d2', 'Flujo', 'SEQUENCE')])
    renderApp('/projects/p1')

    expect(await screen.findByRole('link', { name: 'Flujo' })).toHaveAttribute('href', '/diagrams/d2/view')
  })

  it('un DEVELOPER abre los diagramas en modo lectura y no puede crear', async () => {
    signIn('DEVELOPER')
    list.mockResolvedValue([diagram('d1', 'Dominio')])
    renderApp('/projects/p1')

    expect(await screen.findByRole('link', { name: 'Dominio' })).toHaveAttribute('href', '/diagrams/d1/view')
    expect(screen.queryByRole('button', { name: 'Nuevo diagrama' })).not.toBeInTheDocument()
  })

  it('avisa cuando el proyecto no tiene diagramas', async () => {
    signIn('DESIGNER')
    list.mockResolvedValue([])
    renderApp('/projects/p1')

    expect(await screen.findByText(/Todavía no hay diagramas/)).toBeInTheDocument()
  })

  it('el DESIGNER crea un diagrama y el editor se abre con él', async () => {
    signIn('DESIGNER')
    list.mockResolvedValue([])
    create.mockResolvedValue(diagram('d9', 'Dominio'))
    const { router } = renderApp('/projects/p1')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Nuevo diagrama' }))
    await user.type(await screen.findByLabelText('Nombre'), 'Dominio')
    await user.click(screen.getByRole('button', { name: 'Crear' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/diagrams/d9'))
    expect(create.mock.calls[0][0]).toEqual({ projectId: 'p1', name: 'Dominio', description: '' })
  })

  it('muestra DUPLICATE_DIAGRAM sin cerrar el formulario', async () => {
    signIn('DESIGNER')
    list.mockResolvedValue([])
    create.mockRejectedValue(new ApiError('DUPLICATE_DIAGRAM', 'Ya existe un diagrama con ese nombre.', 409))
    renderApp('/projects/p1')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Nuevo diagrama' }))
    await user.type(await screen.findByLabelText('Nombre'), 'Dominio')
    await user.click(screen.getByRole('button', { name: 'Crear' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Ya existe un diagrama con ese nombre.')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })
})
