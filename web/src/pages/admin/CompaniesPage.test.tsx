import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
  createCompany,
  createCompanyAdmin,
  listCompanies,
  setCompanyActive,
  slugify,
  updateCompany,
} from '@/lib/api/companies'
import { ApiError } from '@/lib/api/errors'
import type { User } from '@/lib/api/types'
import { useAuthStore } from '@/stores/auth-store'
import { renderApp } from '@/test/render'

vi.mock('@/lib/api/companies', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/companies')>()),
  listCompanies: vi.fn(),
  createCompany: vi.fn(),
  updateCompany: vi.fn(),
  setCompanyActive: vi.fn(),
  createCompanyAdmin: vi.fn(),
}))

const list = vi.mocked(listCompanies)
const create = vi.mocked(createCompany)
const update = vi.mocked(updateCompany)
const setActive = vi.mocked(setCompanyActive)
const createAdmin = vi.mocked(createCompanyAdmin)

const acme = { id: 'c1', name: 'Acme', slug: 'acme', active: true, createdAt: '2026-01-15T10:00:00Z' }
const vieja = { id: 'c2', name: 'Vieja SRL', slug: 'vieja', active: false, createdAt: '2025-06-01T10:00:00Z' }

function signInAsSoftwareAdmin() {
  useAuthStore.getState().login('jwt', {
    id: 'u1',
    username: 'admin',
    email: 'admin@platform.com',
    fullName: 'Admin',
    roles: ['SOFTWARE_ADMIN'],
    companyId: 'p1',
    active: true,
    createdAt: '2026-01-01T00:00:00Z',
  } satisfies User)
}

/** Abre el menú de acciones de una fila y elige una opción. */
async function rowAction(user: ReturnType<typeof userEvent.setup>, company: string, action: string | RegExp) {
  await user.click(await screen.findByRole('button', { name: `Acciones de ${company}` }))
  await user.click(await screen.findByRole('menuitem', { name: action }))
}

describe('CU-02 Gestionar empresas', () => {
  beforeEach(signInAsSoftwareAdmin)
  afterEach(() => {
    useAuthStore.getState().logout()
    vi.resetAllMocks()
  })

  it('lista las empresas con su estado', async () => {
    list.mockResolvedValue([acme, vieja])
    renderApp('/admin/companies')

    const acmeRow = (await screen.findByRole('cell', { name: 'Acme' })).closest('tr')!
    expect(within(acmeRow).getByText('Activa')).toBeInTheDocument()
    expect(within(acmeRow).getByRole('cell', { name: 'acme' })).toBeInTheDocument()

    const viejaRow = screen.getByRole('cell', { name: 'Vieja SRL' }).closest('tr')!
    expect(within(viejaRow).getByText('Inactiva')).toBeInTheDocument()
  })

  it('avisa cuando no hay ninguna empresa', async () => {
    list.mockResolvedValue([])
    renderApp('/admin/companies')
    expect(await screen.findByText(/Todavía no hay empresas/)).toBeInTheDocument()
  })

  it('crea una empresa proponiendo el identificador a partir del nombre', async () => {
    list.mockResolvedValue([])
    create.mockResolvedValue({ ...acme, name: 'Café del Sur', slug: 'cafe-del-sur' })
    renderApp('/admin/companies')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Nueva empresa' }))
    await user.type(await screen.findByLabelText('Nombre'), 'Café del Sur')
    expect(screen.getByLabelText('Identificador')).toHaveValue('cafe-del-sur')

    await user.click(screen.getByRole('button', { name: 'Crear' }))
    await waitFor(() => expect(create).toHaveBeenCalledWith({ name: 'Café del Sur', slug: 'cafe-del-sur' }))
  })

  it('muestra DUPLICATE_COMPANY sin cerrar el formulario', async () => {
    list.mockResolvedValue([acme])
    create.mockRejectedValue(new ApiError('DUPLICATE_COMPANY', 'Ya existe una empresa con ese nombre.', 409))
    renderApp('/admin/companies')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Nueva empresa' }))
    await user.type(await screen.findByLabelText('Nombre'), 'Acme')
    await user.click(screen.getByRole('button', { name: 'Crear' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Ya existe una empresa con ese nombre.')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('edita una empresa partiendo de sus datos actuales', async () => {
    list.mockResolvedValue([acme])
    update.mockResolvedValue({ ...acme, name: 'Acme Bolivia' })
    renderApp('/admin/companies')
    const user = userEvent.setup()

    await rowAction(user, 'Acme', 'Editar')
    const name = await screen.findByLabelText('Nombre')
    expect(name).toHaveValue('Acme')

    await user.clear(name)
    await user.type(name, 'Acme Bolivia')
    await user.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() => expect(update).toHaveBeenCalledWith('c1', { name: 'Acme Bolivia', slug: 'acme' }))
  })

  it('desactiva una empresa activa', async () => {
    list.mockResolvedValue([acme])
    setActive.mockResolvedValue({ ...acme, active: false })
    renderApp('/admin/companies')
    const user = userEvent.setup()

    await rowAction(user, 'Acme', 'Desactivar')

    await waitFor(() => expect(setActive).toHaveBeenCalledWith('c1', false))
  })

  it('reactiva una empresa inactiva', async () => {
    list.mockResolvedValue([vieja])
    setActive.mockResolvedValue({ ...vieja, active: true })
    renderApp('/admin/companies')
    const user = userEvent.setup()

    await rowAction(user, 'Vieja SRL', 'Activar')

    await waitFor(() => expect(setActive).toHaveBeenCalledWith('c2', true))
  })

  it('crea el administrador de una empresa', async () => {
    list.mockResolvedValue([acme])
    createAdmin.mockResolvedValue({
      id: 'u9',
      username: 'jefa',
      email: 'jefa@acme.com',
      fullName: 'Jefa Acme',
      roles: ['COMPANY_ADMIN'],
      companyId: 'c1',
      active: true,
      createdAt: '2026-02-01T00:00:00Z',
    })
    renderApp('/admin/companies')
    const user = userEvent.setup()

    await rowAction(user, 'Acme', 'Crear administrador')
    await user.type(await screen.findByLabelText('Usuario'), 'jefa')
    await user.type(screen.getByLabelText('Correo'), 'jefa@acme.com')
    await user.type(screen.getByLabelText('Nombre completo'), 'Jefa Acme')
    await user.type(screen.getByLabelText('Contraseña'), 'clave-larga')
    await user.click(screen.getByRole('button', { name: 'Crear administrador' }))

    await waitFor(() =>
      expect(createAdmin).toHaveBeenCalledWith('c1', {
        username: 'jefa',
        email: 'jefa@acme.com',
        password: 'clave-larga',
        fullName: 'Jefa Acme',
      }),
    )
  })

  it('un DESIGNER no puede entrar a la pantalla', async () => {
    useAuthStore.getState().login('jwt', {
      id: 'u2',
      username: 'designer',
      email: 'd@demo.com',
      fullName: null,
      roles: ['DESIGNER'],
      companyId: 'c1',
      active: true,
      createdAt: '2026-01-01T00:00:00Z',
    })
    const { router } = renderApp('/admin/companies')

    await waitFor(() => expect(router.state.location.pathname).toBe('/projects'))
    expect(list).not.toHaveBeenCalled()
  })
})

describe('slugify', () => {
  it.each([
    ['Café del Sur', 'cafe-del-sur'],
    ['  Acme  S.R.L. ', 'acme-s-r-l'],
    ['Ñandú 2026', 'nandu-2026'],
  ])('«%s» → «%s»', (input, expected) => {
    expect(slugify(input)).toBe(expected)
  })
})
