import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'
import type { Task } from '@/lib/api/codegen'
import { ApiError } from '@/lib/api/errors'
import { deleteTask, listMyTasks, listTasksCreatedByMe, updateTaskStatus } from '@/lib/api/tasks'
import { useAuthStore } from '@/stores/auth-store'
import { queryClient } from '@/lib/query-client'
import TasksPage from './TasksPage'

vi.mock('@/lib/api/tasks', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/tasks')>()),
  listMyTasks: vi.fn(),
  listTasksCreatedByMe: vi.fn(),
  updateTaskStatus: vi.fn(),
  deleteTask: vi.fn(),
}))
vi.mock('sonner', () => ({ toast: Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn() }) }))

function tarea(cambios: Partial<Task> = {}): Task {
  return {
    id: 't1',
    diagramId: 'd1',
    type: 'MANUAL',
    title: 'Revisar el modelo',
    description: 'Comprobar los nombres',
    status: 'PENDING',
    resultJson: null,
    assignedTo: 'u2',
    assignedToName: 'Bruno Dev',
    createdBy: 'u1',
    createdByName: 'Ana Jefa',
    createdAt: '2026-09-20T10:00:00Z',
    startedAt: null,
    completedAt: null,
    ...cambios,
  }
}

function montar() {
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <TasksPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  queryClient.clear()
  vi.mocked(listMyTasks).mockResolvedValue([tarea()])
  vi.mocked(listTasksCreatedByMe).mockResolvedValue([])
})

describe('TasksPage (CU-21)', () => {
  it('muestra las tareas asignadas con su estado', async () => {
    montar()

    expect(await screen.findByText('Revisar el modelo')).toBeInTheDocument()
    expect(screen.getByText('Pendiente')).toBeInTheDocument()
    expect(screen.getByText('Comprobar los nombres')).toBeInTheDocument()
  })

  it('ofrece empezar una tarea pendiente y completar una que está en curso', async () => {
    vi.mocked(listMyTasks).mockResolvedValue([tarea(), tarea({ id: 't2', title: 'Ya empezada', status: 'IN_PROGRESS' })])
    montar()

    expect(await screen.findByRole('button', { name: 'Empezar' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Completar' })).toBeInTheDocument()
  })

  it('una tarea completada ya no ofrece avanzar', async () => {
    vi.mocked(listMyTasks).mockResolvedValue([tarea({ status: 'COMPLETED' })])
    montar()

    expect(await screen.findByText('Completada')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /empezar|completar/i })).not.toBeInTheDocument()
  })

  it('las tareas automáticas se listan pero no se cambian a mano', async () => {
    vi.mocked(listMyTasks).mockResolvedValue([
      tarea({ type: 'CODE_GENERATION', title: 'Generar código', status: 'PENDING' }),
    ])
    montar()

    expect(await screen.findByText('Generación de código')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Empezar' })).not.toBeInTheDocument()
  })

  it('avanza el estado al pulsar', async () => {
    vi.mocked(updateTaskStatus).mockResolvedValue(tarea({ status: 'IN_PROGRESS' }))
    montar()

    await userEvent.click(await screen.findByRole('button', { name: 'Empezar' }))

    await waitFor(() => expect(updateTaskStatus).toHaveBeenCalledWith('t1', 'IN_PROGRESS'))
  })

  it('muestra el mensaje del servidor si el cambio de estado no vale', async () => {
    vi.mocked(updateTaskStatus).mockRejectedValue(
      new ApiError('INVALID_STATE_TRANSITION', 'No se puede pasar de pendiente a completada.', 422),
    )
    montar()

    await userEvent.click(await screen.findByRole('button', { name: 'Empezar' }))

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('No se puede pasar de pendiente a completada.'),
    )
  })

  it('filtra por estado', async () => {
    montar()
    await screen.findByText('Revisar el modelo')

    await userEvent.click(screen.getByRole('button', { name: 'Pendientes' }))

    await waitFor(() => expect(listMyTasks).toHaveBeenLastCalledWith('PENDING'))
  })

  it('cambia a las tareas que creé yo', async () => {
    vi.mocked(listTasksCreatedByMe).mockResolvedValue([tarea({ id: 't9', title: 'Encargada a otro' })])
    montar()

    await userEvent.click(screen.getByRole('tab', { name: 'Creadas por mí' }))

    expect(await screen.findByText('Encargada a otro')).toBeInTheDocument()
    expect(listTasksCreatedByMe).toHaveBeenCalled()
  })

  it('sin tareas lo dice en lugar de mostrar una lista vacía', async () => {
    vi.mocked(listMyTasks).mockResolvedValue([])
    montar()

    expect(await screen.findByText('No tiene tareas asignadas.')).toBeInTheDocument()
  })

  it('muestra quién encargó la tarea que me toca', async () => {
    montar()

    expect(await screen.findByText(/De Ana Jefa/)).toBeInTheDocument()
  })

  it('en «Creadas por mí» muestra a quién se la asigné', async () => {
    vi.mocked(listTasksCreatedByMe).mockResolvedValue([tarea()])
    montar()
    await screen.findByText('Revisar el modelo')

    await userEvent.click(screen.getByRole('tab', { name: 'Creadas por mí' }))

    expect(await screen.findByText(/Para Bruno Dev/)).toBeInTheDocument()
  })

  it('quien encargó la tarea puede editarla y eliminarla', async () => {
    useAuthStore.getState().login('jwt', {
      id: 'u1',
      username: 'ana',
      email: 'ana@demo.com',
      fullName: 'Ana Jefa',
      roles: ['DESIGNER'],
      companyId: 'c1',
      active: true,
      createdAt: '2026-01-01T00:00:00Z',
    })
    montar()

    expect(await screen.findByRole('button', { name: /Editar la tarea/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Eliminar' })).toBeInTheDocument()
    useAuthStore.getState().logout()
  })

  it('quien solo la tiene asignada no puede editarla ni eliminarla', async () => {
    useAuthStore.getState().login('jwt', {
      id: 'u2',
      username: 'bruno',
      email: 'bruno@demo.com',
      fullName: 'Bruno Dev',
      roles: ['DEVELOPER'],
      companyId: 'c1',
      active: true,
      createdAt: '2026-01-01T00:00:00Z',
    })
    montar()
    await screen.findByText('Revisar el modelo')

    expect(screen.queryByRole('button', { name: /Editar la tarea/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Eliminar' })).not.toBeInTheDocument()
    useAuthStore.getState().logout()
  })

  it('eliminar pide confirmación antes de borrar', async () => {
    useAuthStore.getState().login('jwt', {
      id: 'u1',
      username: 'ana',
      email: 'ana@demo.com',
      fullName: 'Ana Jefa',
      roles: ['DESIGNER'],
      companyId: 'c1',
      active: true,
      createdAt: '2026-01-01T00:00:00Z',
    })
    vi.mocked(deleteTask).mockResolvedValue('t1')
    montar()

    await userEvent.click(await screen.findByRole('button', { name: 'Eliminar' }))
    expect(await screen.findByRole('alertdialog')).toHaveTextContent(/Revisar el modelo/)
    expect(deleteTask).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: 'Eliminar la tarea' }))

    await waitFor(() => expect(deleteTask).toHaveBeenCalled())
    expect(vi.mocked(deleteTask).mock.calls[0][0]).toBe('t1')
    useAuthStore.getState().logout()
  })
})
