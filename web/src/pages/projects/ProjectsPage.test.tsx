import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApiError } from '@/lib/api/errors'
import { createProject, listProjects, type Project } from '@/lib/api/projects'
import type { Page } from '@/lib/api/types'
import type { Role, User } from '@/lib/api/types'
import { useAuthStore } from '@/stores/auth-store'
import { renderApp } from '@/test/render'

vi.mock('@/lib/api/projects', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/projects')>()),
  listProjects: vi.fn(),
  createProject: vi.fn(),
}))

const list = vi.mocked(listProjects)
const create = vi.mocked(createProject)

function project(id: string, name: string): Project {
  return {
    id,
    name,
    description: `Descripción de ${name}`,
    ownerId: 'u1',
    ownerName: 'Ana Pérez',
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-02T10:00:00Z',
  }
}

function page(content: Project[], overrides: Partial<Page<Project>> = {}): Page<Project> {
  return { content, page: 0, size: 12, totalElements: content.length, totalPages: 1, ...overrides }
}

function signIn(...roles: Role[]) {
  useAuthStore.getState().login('jwt', {
    id: 'u1',
    username: 'alguien',
    email: 'alguien@demo.com',
    fullName: 'Ana Pérez',
    roles,
    companyId: 'c1',
    active: true,
    createdAt: '2026-01-01T00:00:00Z',
  } satisfies User)
}

describe('CU-05 Consultar proyectos', () => {
  afterEach(() => {
    useAuthStore.getState().logout()
    vi.resetAllMocks()
  })

  it('lista los proyectos de la empresa', async () => {
    signIn('DEVELOPER')
    list.mockResolvedValue(page([project('p1', 'Ventas'), project('p2', 'Inventario')]))
    renderApp('/projects')

    expect(await screen.findByRole('link', { name: 'Ventas' })).toHaveAttribute('href', '/projects/p1')
    expect(screen.getByRole('link', { name: 'Inventario' })).toBeInTheDocument()
    // El responsable aparece en cada tarjeta (el del menú de usuario queda fuera de la lista).
    expect(within(screen.getByRole('list')).getAllByText(/Ana Pérez/)).toHaveLength(2)
  })

  it('una lista vacía es un aviso, no un error', async () => {
    signIn('DESIGNER')
    list.mockResolvedValue(page([]))
    renderApp('/projects')

    expect(await screen.findByText('Todavía no hay proyectos.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('filtra por texto y vuelve a la primera página', async () => {
    signIn('DESIGNER')
    list.mockResolvedValue(page([project('p1', 'Ventas')], { totalPages: 3, totalElements: 30 }))
    renderApp('/projects')
    const user = userEvent.setup()

    await user.type(await screen.findByLabelText('Buscar proyectos'), 'vent')

    await waitFor(() => expect(list).toHaveBeenLastCalledWith({ q: 'vent', page: 0 }))
  })

  it('avisa cuando el filtro no encuentra nada', async () => {
    signIn('DESIGNER')
    list.mockResolvedValue(page([]))
    renderApp('/projects')
    const user = userEvent.setup()

    await user.type(await screen.findByLabelText('Buscar proyectos'), 'zzz')

    expect(await screen.findByText(/Ningún proyecto coincide con «zzz»/)).toBeInTheDocument()
  })

  it('pasa a la página siguiente', async () => {
    signIn('DESIGNER')
    list.mockResolvedValue(page([project('p1', 'Ventas')], { totalPages: 2, totalElements: 20 }))
    renderApp('/projects')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: /Siguiente/ }))

    await waitFor(() => expect(list).toHaveBeenLastCalledWith({ q: '', page: 1 }))
  })

  it('muestra el error del backend si la consulta falla', async () => {
    signIn('DESIGNER')
    list.mockRejectedValue(new ApiError('NETWORK_ERROR', 'No se pudo conectar con el servidor.'))
    renderApp('/projects')

    expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo conectar con el servidor.')
  })
})

describe('CU-04 Crear proyecto', () => {
  afterEach(() => {
    useAuthStore.getState().logout()
    vi.resetAllMocks()
  })

  it('el DESIGNER crea un proyecto', async () => {
    signIn('DESIGNER')
    list.mockResolvedValue(page([]))
    create.mockResolvedValue(project('p9', 'Nuevo'))
    renderApp('/projects')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Nuevo proyecto' }))
    await user.type(await screen.findByLabelText('Nombre'), 'Nuevo')
    await user.type(screen.getByLabelText('Descripción'), 'Un proyecto de prueba')
    await user.click(screen.getByRole('button', { name: 'Crear' }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][0]).toEqual({ name: 'Nuevo', description: 'Un proyecto de prueba' })
  })

  it('muestra DUPLICATE_PROJECT sin cerrar el formulario', async () => {
    signIn('DESIGNER')
    list.mockResolvedValue(page([]))
    create.mockRejectedValue(new ApiError('DUPLICATE_PROJECT', 'Ya existe un proyecto con ese nombre.', 409))
    renderApp('/projects')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Nuevo proyecto' }))
    await user.type(await screen.findByLabelText('Nombre'), 'Ventas')
    await user.click(screen.getByRole('button', { name: 'Crear' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Ya existe un proyecto con ese nombre.')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('el DEVELOPER no ve el botón de crear', async () => {
    signIn('DEVELOPER')
    list.mockResolvedValue(page([project('p1', 'Ventas')]))
    renderApp('/projects')

    await screen.findByRole('link', { name: 'Ventas' })
    expect(screen.queryByRole('button', { name: 'Nuevo proyecto' })).not.toBeInTheDocument()
  })

  it('el COMPANY_ADMIN sí lo ve', async () => {
    signIn('COMPANY_ADMIN')
    list.mockResolvedValue(page([]))
    renderApp('/projects')

    expect(await screen.findByRole('button', { name: 'Nuevo proyecto' })).toBeInTheDocument()
  })
})
