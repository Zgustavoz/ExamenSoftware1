import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { deleteProject, updateProject, type Project } from '@/lib/api/projects'
import type { Role, User } from '@/lib/api/types'
import { queryClient } from '@/lib/query-client'
import { useAuthStore } from '@/stores/auth-store'
import { ProjectActions } from './ProjectActions'

vi.mock('@/lib/api/projects', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/projects')>()),
  updateProject: vi.fn(),
  deleteProject: vi.fn(),
}))
vi.mock('sonner', () => ({ toast: Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn() }) }))

const proyecto: Project = {
  id: 'p1',
  name: 'Ventas',
  description: 'Módulo de ventas',
  ownerId: 'u-duenno',
  ownerName: 'Ana',
  createdAt: '2026-09-20T00:00:00Z',
  updatedAt: '2026-09-20T00:00:00Z',
}

function sesion(id: string, roles: Role[]): User {
  return {
    id,
    username: 'quien-sea',
    email: 'a@b.test',
    fullName: 'Quien Sea',
    roles,
    companyId: 'c1',
    active: true,
    createdAt: '2026-09-20T00:00:00Z',
  }
}

function montar(user: User) {
  useAuthStore.getState().login('jwt', user)
  return render(
    <QueryClientProvider client={queryClient}>
      <ProjectActions project={proyecto} />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  queryClient.clear()
  useAuthStore.getState().logout()
})

describe('ProjectActions (CU-23)', () => {
  it('no ofrece acciones a un diseñador que no es el propietario', () => {
    montar(sesion('otro', ['DESIGNER']))
    expect(screen.queryByRole('button', { name: /acciones/i })).not.toBeInTheDocument()
  })

  it('el propietario ve editar y eliminar', async () => {
    montar(sesion('u-duenno', ['DESIGNER']))

    await userEvent.click(screen.getByRole('button', { name: 'Acciones de Ventas' }))

    expect(await screen.findByRole('menuitem', { name: 'Editar' })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Eliminar' })).toBeInTheDocument()
  })

  it('el administrador de la empresa también, aunque no sea suyo', async () => {
    montar(sesion('otro', ['COMPANY_ADMIN']))

    await userEvent.click(screen.getByRole('button', { name: 'Acciones de Ventas' }))

    expect(await screen.findByRole('menuitem', { name: 'Editar' })).toBeInTheDocument()
  })

  it('avisa de lo que se pierde antes de eliminar y no borra si se cancela', async () => {
    montar(sesion('u-duenno', ['DESIGNER']))

    await userEvent.click(screen.getByRole('button', { name: 'Acciones de Ventas' }))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Eliminar' }))

    const dialogo = await screen.findByRole('alertdialog')
    expect(dialogo).toHaveTextContent('¿Eliminar el proyecto «Ventas»?')
    expect(dialogo).toHaveTextContent(/diagramas/i)
    expect(dialogo).toHaveTextContent(/tareas/i)
    expect(dialogo).toHaveTextContent(/código generado/i)
    expect(dialogo).toHaveTextContent(/conversaciones/i)

    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(deleteProject).not.toHaveBeenCalled()
  })

  it('elimina al confirmar', async () => {
    vi.mocked(deleteProject).mockResolvedValue()
    montar(sesion('u-duenno', ['DESIGNER']))

    await userEvent.click(screen.getByRole('button', { name: 'Acciones de Ventas' }))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Eliminar' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Eliminar' }))

    await waitFor(() => expect(deleteProject).toHaveBeenCalledWith('p1'))
  })

  it('al editar, el formulario llega con los datos del proyecto y guarda los cambios', async () => {
    vi.mocked(updateProject).mockResolvedValue({ ...proyecto, name: 'Ventas y postventa' })
    montar(sesion('u-duenno', ['DESIGNER']))

    await userEvent.click(screen.getByRole('button', { name: 'Acciones de Ventas' }))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Editar' }))

    const nombre = await screen.findByLabelText('Nombre')
    expect(nombre).toHaveValue('Ventas')
    expect(screen.getByLabelText('Descripción')).toHaveValue('Módulo de ventas')

    await userEvent.clear(nombre)
    await userEvent.type(nombre, 'Ventas y postventa')
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() =>
      expect(updateProject).toHaveBeenCalledWith('p1', { name: 'Ventas y postventa', description: 'Módulo de ventas' }),
    )
  })
})
