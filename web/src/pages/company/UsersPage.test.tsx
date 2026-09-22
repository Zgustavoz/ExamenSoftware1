import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApiError } from '@/lib/api/errors'
import type { Role, User } from '@/lib/api/types'
import { createUser, listUsers, setUserActive, updateUser } from '@/lib/api/users'
import { useAuthStore } from '@/stores/auth-store'
import { renderApp } from '@/test/render'

vi.mock('@/lib/api/users', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/users')>()),
  listUsers: vi.fn(),
  createUser: vi.fn(),
  updateUser: vi.fn(),
  setUserActive: vi.fn(),
}))

const list = vi.mocked(listUsers)
const create = vi.mocked(createUser)
const update = vi.mocked(updateUser)
const setActive = vi.mocked(setUserActive)

function makeUser(id: string, username: string, roles: Role[], active = true): User {
  return {
    id,
    username,
    email: `${username}@demo.com`,
    fullName: username.toUpperCase(),
    roles,
    companyId: 'c1',
    active,
    createdAt: '2026-01-01T00:00:00Z',
  }
}

const admin = makeUser('u1', 'companyadmin', ['COMPANY_ADMIN'])
const designer = makeUser('u2', 'designer', ['DESIGNER'])
const inactivo = makeUser('u3', 'antiguo', ['DEVELOPER'], false)

function signInAsCompanyAdmin() {
  useAuthStore.getState().login('jwt', admin)
}

async function rowAction(user: ReturnType<typeof userEvent.setup>, username: string, action: string | RegExp) {
  await user.click(await screen.findByRole('button', { name: `Acciones de ${username}` }))
  await user.click(await screen.findByRole('menuitem', { name: action }))
}

describe('CU-03 Gestionar usuarios de empresa', () => {
  beforeEach(signInAsCompanyAdmin)
  afterEach(() => {
    useAuthStore.getState().logout()
    vi.resetAllMocks()
  })

  it('lista los usuarios con sus roles y estado', async () => {
    list.mockResolvedValue([admin, designer, inactivo])
    renderApp('/company/users')

    const row = (await screen.findByRole('cell', { name: 'designer' })).closest('tr')!
    expect(within(row).getByText('Diseñador')).toBeInTheDocument()
    expect(within(row).getByText('Activo')).toBeInTheDocument()

    const inactiveRow = screen.getByRole('cell', { name: 'antiguo' }).closest('tr')!
    expect(within(inactiveRow).getByText('Inactivo')).toBeInTheDocument()
  })

  it('crea un usuario con los roles marcados', async () => {
    list.mockResolvedValue([admin])
    create.mockResolvedValue(makeUser('u9', 'nueva', ['DESIGNER', 'DEVELOPER']))
    renderApp('/company/users')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Nuevo usuario' }))
    await user.type(await screen.findByLabelText('Usuario'), 'nueva')
    await user.type(screen.getByLabelText('Correo'), 'nueva@demo.com')
    await user.type(screen.getByLabelText('Nombre completo'), 'Nueva Persona')
    await user.type(screen.getByLabelText('Contraseña'), 'clave-larga')
    await user.click(screen.getByLabelText('Desarrollador'))
    await user.click(screen.getByRole('button', { name: 'Crear' }))

    await waitFor(() =>
      expect(create).toHaveBeenCalledWith({
        username: 'nueva',
        email: 'nueva@demo.com',
        fullName: 'Nueva Persona',
        password: 'clave-larga',
        roles: ['DESIGNER', 'DEVELOPER'],
      }),
    )
  })

  it('no ofrece el rol SOFTWARE_ADMIN', async () => {
    list.mockResolvedValue([admin])
    renderApp('/company/users')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Nuevo usuario' }))
    await screen.findByLabelText('Diseñador')

    expect(screen.queryByLabelText(/plataforma/i)).not.toBeInTheDocument()
  })

  it('no deja crear un usuario sin ningún rol', async () => {
    list.mockResolvedValue([admin])
    renderApp('/company/users')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Nuevo usuario' }))
    await user.click(await screen.findByLabelText('Diseñador')) // desmarca el rol por defecto

    expect(screen.getByRole('button', { name: 'Crear' })).toBeDisabled()
  })

  it('muestra DUPLICATE_USER sin cerrar el formulario', async () => {
    list.mockResolvedValue([admin])
    create.mockRejectedValue(new ApiError('DUPLICATE_USER', 'El usuario o el correo ya existe en la empresa.', 409))
    renderApp('/company/users')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Nuevo usuario' }))
    await user.type(await screen.findByLabelText('Usuario'), 'designer')
    await user.type(screen.getByLabelText('Correo'), 'designer@demo.com')
    await user.type(screen.getByLabelText('Contraseña'), 'clave-larga')
    await user.click(screen.getByRole('button', { name: 'Crear' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('El usuario o el correo ya existe en la empresa.')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('al editar, la contraseña vacía viaja vacía (sin cambio)', async () => {
    list.mockResolvedValue([admin, designer])
    update.mockResolvedValue({ ...designer, fullName: 'Ana Diseñadora' })
    renderApp('/company/users')
    const user = userEvent.setup()

    await rowAction(user, 'designer', 'Editar')
    const fullName = await screen.findByLabelText('Nombre completo')
    await user.clear(fullName)
    await user.type(fullName, 'Ana Diseñadora')
    await user.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() =>
      expect(update).toHaveBeenCalledWith('u2', {
        username: 'designer',
        email: 'designer@demo.com',
        fullName: 'Ana Diseñadora',
        password: '',
        roles: ['DESIGNER'],
      }),
    )
  })

  it('desactiva a otro usuario', async () => {
    list.mockResolvedValue([admin, designer])
    setActive.mockResolvedValue({ ...designer, active: false })
    renderApp('/company/users')
    const user = userEvent.setup()

    await rowAction(user, 'designer', 'Desactivar')

    await waitFor(() => expect(setActive).toHaveBeenCalledWith('u2', false))
  })

  it('no ofrece desactivar al propio usuario', async () => {
    list.mockResolvedValue([admin, designer])
    renderApp('/company/users')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Acciones de companyadmin' }))

    expect(await screen.findByRole('menuitem', { name: 'Editar' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Desactivar' })).not.toBeInTheDocument()
  })

  it('un DESIGNER no puede entrar a la pantalla', async () => {
    useAuthStore.getState().login('jwt', designer)
    const { router } = renderApp('/company/users')

    await waitFor(() => expect(router.state.location.pathname).toBe('/projects'))
    expect(list).not.toHaveBeenCalled()
  })
})
