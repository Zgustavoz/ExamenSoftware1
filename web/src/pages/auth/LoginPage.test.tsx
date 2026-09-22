import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { login } from '@/lib/api/auth'
import { ApiError } from '@/lib/api/errors'
import type { Role, User } from '@/lib/api/types'
import { useAuthStore } from '@/stores/auth-store'
import { renderApp } from '@/test/render'

vi.mock('@/lib/api/auth', () => ({ login: vi.fn(), fetchMe: vi.fn() }))
const loginMock = vi.mocked(login)

function userWith(...roles: Role[]): User {
  return {
    id: 'u1',
    username: 'designer',
    email: 'designer@demo.com',
    fullName: 'Diseñadora Demo',
    roles,
    companyId: 'c1',
    active: true,
    createdAt: '2026-01-01T00:00:00Z',
  }
}

/** Rellena el formulario y envía. */
async function submitLogin(password = 'secreta') {
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('Empresa'), 'demo')
  await user.type(screen.getByLabelText('Usuario'), 'designer')
  await user.type(screen.getByLabelText('Contraseña'), password)
  await user.click(screen.getByRole('button', { name: 'Ingresar' }))
  return user
}

describe('CU-01 Iniciar sesión', () => {
  afterEach(() => {
    useAuthStore.getState().logout()
    vi.resetAllMocks()
  })

  it('guarda la sesión y redirige según el rol', async () => {
    loginMock.mockResolvedValue({ token: 'jwt-123', user: userWith('DESIGNER') })
    const { router } = renderApp('/login')

    await submitLogin()

    await waitFor(() => expect(router.state.location.pathname).toBe('/projects'))
    expect(loginMock.mock.calls[0][0]).toEqual({ companySlug: 'demo', username: 'designer', password: 'secreta' })
    expect(useAuthStore.getState().token).toBe('jwt-123')
  })

  it('el SOFTWARE_ADMIN entra a la administración de empresas', async () => {
    loginMock.mockResolvedValue({ token: 'jwt-123', user: userWith('SOFTWARE_ADMIN') })
    const { router } = renderApp('/login')

    await submitLogin()

    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/companies'))
  })

  it.each([
    ['INVALID_CREDENTIALS', 401, 'Empresa, usuario o contraseña incorrectos.'],
    ['USER_INACTIVE', 403, 'El usuario está inactivo.'],
    ['COMPANY_DISABLED', 403, 'La empresa está deshabilitada.'],
  ] as const)('muestra el mensaje del backend ante %s y no inicia sesión', async (code, status, message) => {
    loginMock.mockRejectedValue(new ApiError(code, message, status))
    const { router } = renderApp('/login')

    await submitLogin('mala')

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(useAuthStore.getState().token).toBeNull()
    expect(router.state.location.pathname).toBe('/login')
  })

  it('la contraseña se puede mostrar y volver a ocultar', async () => {
    renderApp('/login')
    const user = userEvent.setup()
    const password = await screen.findByLabelText('Contraseña')

    expect(password).toHaveAttribute('type', 'password')
    await user.click(screen.getByRole('button', { name: 'Mostrar contraseña' }))
    expect(password).toHaveAttribute('type', 'text')
    await user.click(screen.getByRole('button', { name: 'Ocultar contraseña' }))
    expect(password).toHaveAttribute('type', 'password')
  })

  it('una ruta protegida guarda el destino y vuelve a él tras iniciar sesión', async () => {
    loginMock.mockResolvedValue({ token: 'jwt-123', user: userWith('COMPANY_ADMIN') })
    const { router } = renderApp('/company/users')

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    await submitLogin()

    await waitFor(() => expect(router.state.location.pathname).toBe('/company/users'))
  })
})
