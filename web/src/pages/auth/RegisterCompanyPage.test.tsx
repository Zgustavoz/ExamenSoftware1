import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { registerCompany } from '@/lib/api/auth'
import { ApiError } from '@/lib/api/errors'
import { queryClient } from '@/lib/query-client'
import type { Role } from '@/lib/api/types'
import { useAuthStore } from '@/stores/auth-store'
import RegisterCompanyPage from './RegisterCompanyPage'

vi.mock('@/lib/api/auth', () => ({ registerCompany: vi.fn() }))

const navigate = vi.fn()
vi.mock('react-router-dom', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router-dom')>()),
  useNavigate: () => navigate,
}))

const sesion = {
  token: 'jwt-de-prueba',
  user: {
    id: 'u1',
    username: 'jefe',
    email: 'jefe@nueva.test',
    fullName: 'Jefa',
    roles: ['COMPANY_ADMIN'] as Role[],
    companyId: 'c1',
    active: true,
    createdAt: '2026-09-20T00:00:00Z',
  },
}

function montar() {
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <RegisterCompanyPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

async function rellenar(datos: { empresa?: string } = {}) {
  await userEvent.type(screen.getByLabelText('Nombre de la empresa'), datos.empresa ?? 'Mi Empresa Nueva')
  await userEvent.type(screen.getByLabelText('Usuario'), 'jefe')
  await userEvent.type(screen.getByLabelText('Correo'), 'jefe@nueva.test')
  await userEvent.type(screen.getByLabelText('Contraseña'), 'Password#123')
}

beforeEach(() => {
  vi.clearAllMocks()
  queryClient.clear()
  useAuthStore.getState().logout()
})

describe('RegisterCompanyPage (CU-22)', () => {
  it('propone el identificador a partir del nombre, sin acentos ni espacios', async () => {
    montar()

    await userEvent.type(screen.getByLabelText('Nombre de la empresa'), 'Diseño Gráfico S.A.')

    expect(screen.getByLabelText('Identificador')).toHaveValue('diseno-grafico-s-a')
  })

  it('respeta el identificador si el usuario lo escribe a mano', async () => {
    montar()
    const identificador = screen.getByLabelText('Identificador')

    await userEvent.type(identificador, 'mi-slug')
    await userEvent.type(screen.getByLabelText('Nombre de la empresa'), 'Otro Nombre')

    expect(identificador).toHaveValue('mi-slug')
  })

  it('registra la empresa, guarda la sesión y lleva a los usuarios de la empresa', async () => {
    vi.mocked(registerCompany).mockResolvedValue(sesion)
    montar()

    await rellenar()
    await userEvent.click(screen.getByRole('button', { name: 'Registrar empresa' }))

    // TanStack Query pasa un segundo argumento con su contexto, así que se compara solo el primero.
    await waitFor(() => expect(registerCompany).toHaveBeenCalledOnce())
    expect(vi.mocked(registerCompany).mock.calls[0]?.[0]).toEqual({
      companyName: 'Mi Empresa Nueva',
      companySlug: 'mi-empresa-nueva',
      admin: { fullName: '', username: 'jefe', email: 'jefe@nueva.test', password: 'Password#123' },
    })
    await waitFor(() => expect(useAuthStore.getState().token).toBe('jwt-de-prueba'))
    expect(navigate).toHaveBeenCalledWith('/company/users', { replace: true })
  })

  it('muestra el mensaje del servidor si la empresa ya existe y no inicia sesión', async () => {
    vi.mocked(registerCompany).mockRejectedValue(
      new ApiError('DUPLICATE_COMPANY', 'Ya existe una empresa con ese nombre o identificador.', 409),
    )
    montar()

    await rellenar()
    await userEvent.click(screen.getByRole('button', { name: 'Registrar empresa' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Ya existe una empresa con ese nombre o identificador.')
    expect(useAuthStore.getState().token).toBeNull()
    expect(navigate).not.toHaveBeenCalled()
  })

  it('ofrece volver al inicio de sesión', () => {
    montar()
    expect(screen.getByRole('link', { name: 'Iniciar sesión' })).toHaveAttribute('href', '/login')
  })
})
