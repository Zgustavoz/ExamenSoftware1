import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Role, User } from '@/lib/api/types'
import { useAuthStore } from '@/stores/auth-store'
import { renderApp } from '@/test/render'

vi.mock('@/lib/api/companies', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/companies')>()),
  listCompanies: vi.fn(async () => []),
}))

function signIn(...roles: Role[]): User {
  const user: User = {
    id: 'u1',
    username: 'designer',
    email: 'designer@demo.com',
    fullName: 'Ana Pérez',
    roles,
    companyId: 'c1',
    active: true,
    createdAt: '2026-01-01T00:00:00Z',
  }
  useAuthStore.getState().login('jwt', user)
  return user
}

async function navLinks() {
  return within(await screen.findByRole('navigation', { name: 'Principal' }))
    .getAllByRole('link')
    .map((link) => link.textContent)
}

describe('marco de la aplicación', () => {
  afterEach(() => useAuthStore.getState().logout())

  it('el SOFTWARE_ADMIN solo ve Empresas y Notificaciones', async () => {
    signIn('SOFTWARE_ADMIN')
    renderApp('/admin/companies')
    expect(await navLinks()).toEqual(['Empresas', 'Notificaciones'])
  })

  it('el COMPANY_ADMIN ve Usuarios y Proyectos, no Empresas', async () => {
    signIn('COMPANY_ADMIN')
    renderApp('/company/users')
    expect(await navLinks()).toEqual(['Usuarios', 'Proyectos', 'Tareas', 'Notificaciones'])
  })

  it('el DESIGNER solo ve Proyectos y Notificaciones', async () => {
    signIn('DESIGNER')
    renderApp('/projects')
    expect(await navLinks()).toEqual(['Proyectos', 'Tareas', 'Notificaciones'])
  })

  it('cerrar sesión limpia el store y lleva al login', async () => {
    signIn('DEVELOPER')
    const { router } = renderApp('/projects')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Menú de usuario' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Cerrar sesión' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    expect(useAuthStore.getState().token).toBeNull()
  })
})
