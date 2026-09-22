import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'
import { disablePush, enablePush, getPushStatus, PushError } from '@/lib/push/push'
import { PushSettings } from './PushSettings'

vi.mock('sonner', () => ({ toast: Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn() }) }))
vi.mock('@/lib/push/push', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/push/push')>()),
  getPushStatus: vi.fn(),
  enablePush: vi.fn(),
  disablePush: vi.fn(),
}))

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(disablePush).mockResolvedValue()
})

describe('PushSettings', () => {
  it('sin configuración explica que el push no está disponible y no ofrece acciones', async () => {
    vi.mocked(getPushStatus).mockResolvedValue('not-configured')
    render(<PushSettings />)

    expect(await screen.findByText(/no están configurados en este entorno/i)).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('con el permiso bloqueado explica cómo desbloquearlo', async () => {
    vi.mocked(getPushStatus).mockResolvedValue('denied')
    render(<PushSettings />)

    expect(await screen.findByText(/ajustes del sitio/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /activar/i })).not.toBeInTheDocument()
  })

  it('un navegador sin soporte lo dice', async () => {
    vi.mocked(getPushStatus).mockResolvedValue('unsupported')
    render(<PushSettings />)
    expect(await screen.findByText(/no admite avisos push/i)).toBeInTheDocument()
  })

  it('activa los avisos con un clic y pasa a estado activo', async () => {
    vi.mocked(getPushStatus).mockResolvedValue('prompt')
    vi.mocked(enablePush).mockResolvedValue('token')
    render(<PushSettings />)

    await userEvent.click(await screen.findByRole('button', { name: /activar avisos/i }))

    expect(enablePush).toHaveBeenCalledOnce()
    expect(await screen.findByRole('button', { name: /desactivar/i })).toBeInTheDocument()
    expect(toast.success).toHaveBeenCalledWith('Avisos activados en este navegador.')
  })

  it('si el usuario deniega el permiso lo refleja y muestra el motivo', async () => {
    vi.mocked(getPushStatus).mockResolvedValue('prompt')
    vi.mocked(enablePush).mockRejectedValue(new PushError('denied', 'Los avisos están bloqueados.'))
    render(<PushSettings />)

    await userEvent.click(await screen.findByRole('button', { name: /activar avisos/i }))

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Los avisos están bloqueados.'))
    expect(screen.queryByRole('button', { name: /activar avisos/i })).not.toBeInTheDocument()
  })

  it('un fallo técnico deja reintentar', async () => {
    vi.mocked(getPushStatus).mockResolvedValue('prompt')
    vi.mocked(enablePush).mockRejectedValue(new PushError('failed', 'No se pudieron activar los avisos.'))
    render(<PushSettings />)

    await userEvent.click(await screen.findByRole('button', { name: /activar avisos/i }))

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('No se pudieron activar los avisos.'))
    expect(screen.getByRole('button', { name: /activar avisos/i })).toBeEnabled()
  })

  it('desactiva los avisos y vuelve a ofrecer activarlos', async () => {
    vi.mocked(getPushStatus).mockResolvedValue('enabled')
    render(<PushSettings />)

    await userEvent.click(await screen.findByRole('button', { name: /desactivar/i }))

    expect(disablePush).toHaveBeenCalledOnce()
    expect(await screen.findByRole('button', { name: /activar avisos/i })).toBeInTheDocument()
  })
})
