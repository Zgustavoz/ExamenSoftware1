import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApiError } from '@/lib/api/errors'
import { listNotifications, markNotificationRead, type Notification } from '@/lib/api/notifications'
import type { Page, User } from '@/lib/api/types'
import { useAuthStore } from '@/stores/auth-store'
import { renderApp } from '@/test/render'

vi.mock('@/lib/api/notifications', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/notifications')>()),
  listNotifications: vi.fn(),
  markNotificationRead: vi.fn(),
}))

const list = vi.mocked(listNotifications)
const markRead = vi.mocked(markNotificationRead)

function notification(overrides: Partial<Notification> = {}): Notification {
  return {
    id: 'n1',
    title: 'Ya puede descargar el código',
    message: 'El código de «Dominio» está disponible.',
    type: 'CODE_READY',
    payload: null,
    read: false,
    createdAt: '2026-03-08T10:00:00Z',
    ...overrides,
  }
}

function page(content: Notification[]): Page<Notification> {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: 1 }
}

function signIn() {
  useAuthStore.getState().login('jwt', {
    id: 'u1',
    username: 'designer',
    email: 'd@demo.com',
    fullName: 'Ana',
    roles: ['DESIGNER'],
    companyId: 'c1',
    active: true,
    createdAt: '2026-01-01T00:00:00Z',
  } satisfies User)
}

describe('CU-19 Notificaciones', () => {
  beforeEach(signIn)
  afterEach(() => {
    useAuthStore.getState().logout()
    vi.resetAllMocks()
  })

  it('lista las notificaciones con su tipo y estado', async () => {
    list.mockResolvedValue(
      page([
        notification(),
        notification({
          id: 'n2',
          title: 'Le asignaron una tarea',
          message: 'Revisar el diagrama de ventas.',
          type: 'TASK_ASSIGNED',
          read: true,
        }),
      ]),
    )
    renderApp('/notifications')

    expect(await screen.findByText('El código de «Dominio» está disponible.')).toBeInTheDocument()
    // La etiqueta del tipo traduce el código que envía el backend.
    expect(screen.getByText('Código listo')).toBeInTheDocument()
    expect(screen.getByText('Tarea asignada')).toBeInTheDocument()
    expect(screen.getByText('Sin leer')).toBeInTheDocument()
  })

  it('marca una notificación como leída', async () => {
    list.mockResolvedValue(page([notification()]))
    markRead.mockResolvedValue(notification({ read: true }))
    renderApp('/notifications')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: /Marcar como leída/ }))

    await waitFor(() => expect(markRead).toHaveBeenCalled())
    expect(markRead.mock.calls[0][0]).toBe('n1')
  })

  it('una notificación ya leída no ofrece marcarla', async () => {
    list.mockResolvedValue(page([notification({ read: true })]))
    renderApp('/notifications')

    await screen.findByText('Ya puede descargar el código')
    expect(screen.queryByRole('button', { name: /Marcar como leída/ })).not.toBeInTheDocument()
    expect(screen.queryByText('Sin leer')).not.toBeInTheDocument()
  })

  it('filtra por las que están sin leer', async () => {
    list.mockResolvedValue(page([notification()]))
    renderApp('/notifications')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Solo sin leer' }))

    await waitFor(() => expect(list).toHaveBeenLastCalledWith({ unreadOnly: true }))
  })

  it('sin notificaciones avisa, sin tratarlo como error', async () => {
    list.mockResolvedValue(page([]))
    renderApp('/notifications')

    expect(await screen.findByText('Todavía no tiene notificaciones.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('muestra el error del backend si la consulta falla', async () => {
    list.mockRejectedValue(new ApiError('NETWORK_ERROR', 'No se pudo conectar con el servidor.'))
    renderApp('/notifications')

    expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo conectar con el servidor.')
  })
})
