import { screen, waitFor } from '@testing-library/react'
import type { Role, User } from '@/lib/api/types'
import { useAuthStore } from '@/stores/auth-store'
import { renderApp } from '@/test/render'

// Las pantallas reales se cargan de forma diferida; aquí solo interesa a cuál se llega.
vi.mock('@/lib/api/companies', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/companies')>()),
  listCompanies: vi.fn(async () => []),
}))

function userWith(...roles: Role[]): User {
  return {
    id: 'u1',
    username: 'x',
    email: 'x@demo.com',
    fullName: null,
    roles,
    companyId: 'c1',
    active: true,
    createdAt: '2026-01-01T00:00:00Z',
  }
}

describe('guardas de ruta', () => {
  afterEach(() => useAuthStore.getState().logout())

  it('sin sesión, cualquier ruta protegida lleva al login', async () => {
    const { router } = renderApp('/projects')
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
  })

  it.each([
    ['SOFTWARE_ADMIN', '/', 'Empresas'],
    ['COMPANY_ADMIN', '/admin/companies', 'Usuarios de la empresa'],
    ['DESIGNER', '/company/users', 'Proyectos'],
    ['DEVELOPER', '/admin/companies', 'Proyectos'],
  ] as const)('%s que entra a %s termina en «%s»', async (role, path, heading) => {
    useAuthStore.getState().login('t', userWith(role))
    renderApp(path)
    expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument()
  })

  it('con sesión, el login redirige a la pantalla del rol', async () => {
    useAuthStore.getState().login('t', userWith('COMPANY_ADMIN'))
    const { router } = renderApp('/login')
    await waitFor(() => expect(router.state.location.pathname).toBe('/company/users'))
    // La pantalla se carga de forma diferida: que la ruta ya haya cambiado no significa que esté montada.
    expect(await screen.findByRole('heading', { name: 'Usuarios de la empresa' })).toBeInTheDocument()
  })

  it('una dirección desconocida muestra la página de error', () => {
    useAuthStore.getState().login('t', userWith('DESIGNER'))
    renderApp('/no-existe')
    expect(screen.getByRole('heading', { name: 'Página no encontrada' })).toBeInTheDocument()
  })
})
