import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
  downloadGeneratedCode,
  fileNameFrom,
  generateBackendCode,
  listGenerations,
  listTasks,
  type Generation,
  type Task,
} from '@/lib/api/codegen'
import { getDiagram, type Diagram } from '@/lib/api/diagrams'
import { ApiError } from '@/lib/api/errors'
import type { Role, User } from '@/lib/api/types'
import { EMPTY_CLASS_CONTENT } from '@/lib/diagram/types'
import { useAuthStore } from '@/stores/auth-store'
import { renderApp } from '@/test/render'

vi.mock('@/lib/api/codegen', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/codegen')>()),
  generateBackendCode: vi.fn(),
  listTasks: vi.fn(),
  listGenerations: vi.fn(),
  downloadGeneratedCode: vi.fn(),
}))
vi.mock('@/lib/api/diagrams', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/diagrams')>()),
  getDiagram: vi.fn(),
}))

const generate = vi.mocked(generateBackendCode)
const tasks = vi.mocked(listTasks)
const generations = vi.mocked(listGenerations)
const download = vi.mocked(downloadGeneratedCode)
const diagramQuery = vi.mocked(getDiagram)

const diagram: Diagram = {
  id: 'd1',
  projectId: 'p1',
  name: 'Dominio',
  description: null,
  type: 'CLASS',
  contentJson: EMPTY_CLASS_CONTENT,
  version: 3,
  sourceDiagramId: null,
  createdBy: 'u1',
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

function task(overrides: Partial<Task> = {}): Task {
  return {
    id: 't1',
    diagramId: 'd1',
    type: 'CODE_GENERATION',
    title: 'Generar código JAVA',
    description: null,
    status: 'COMPLETED',
    resultJson: null,
    assignedTo: 'u1',
    createdBy: 'u1',
    createdAt: '2026-03-06T10:00:00Z',
    startedAt: '2026-03-06T10:00:01Z',
    completedAt: '2026-03-06T10:00:04Z',
    ...overrides,
  }
}

const generation: Generation = {
  taskId: 't1',
  diagramId: 'd1',
  language: 'JAVA',
  status: 'SUCCESS',
  createdAt: '2026-03-06T10:00:04Z',
  files: [
    { id: 'f1', fileName: 'src/main/java/com/generated/app/model/Cliente.java', status: 'SUCCESS' },
    { id: 'f2', fileName: 'pom.xml', status: 'SUCCESS' },
  ],
}

function signIn(...roles: Role[]) {
  useAuthStore.getState().login('jwt', {
    id: 'u1',
    username: 'alguien',
    email: 'a@demo.com',
    fullName: 'Ana',
    roles,
    companyId: 'c1',
    active: true,
    createdAt: '2026-01-01T00:00:00Z',
  } satisfies User)
}

describe('CU-14 Generar código backend', () => {
  beforeEach(() => {
    diagramQuery.mockResolvedValue(diagram)
    generations.mockResolvedValue([])
  })
  afterEach(() => {
    useAuthStore.getState().logout()
    vi.resetAllMocks()
  })

  it('el DESIGNER lanza la generación y ve la tarea completada (CP-05)', async () => {
    signIn('DESIGNER')
    tasks.mockResolvedValue([])
    generate.mockResolvedValue(task())
    renderApp('/diagrams/d1/code')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Generar código' }))

    await waitFor(() => expect(generate).toHaveBeenCalledWith('d1'))
  })

  it('un diagrama incompleto se rechaza con su mensaje y sin crear tarea (CP-06)', async () => {
    signIn('DESIGNER')
    tasks.mockResolvedValue([])
    generate.mockRejectedValue(
      new ApiError(
        'DIAGRAM_INCOMPLETE',
        'El diagrama debe tener clases con atributos y métodos para poder generar código.',
        422,
      ),
    )
    renderApp('/diagrams/d1/code')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Generar código' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'El diagrama debe tener clases con atributos y métodos',
    )
    expect(screen.getByText(/Todavía no se generó código/)).toBeInTheDocument()
  })

  it('muestra el historial de tareas con su estado', async () => {
    signIn('DESIGNER')
    tasks.mockResolvedValue([task(), task({ id: 't2', status: 'FAILED' })])
    renderApp('/diagrams/d1/code')

    await screen.findAllByText('Generar código JAVA')
    expect(screen.getByText('Completada')).toBeInTheDocument()
    expect(screen.getByText('Fallida')).toBeInTheDocument()
  })

  it('ignora las tareas que no son de generación de código', async () => {
    signIn('DESIGNER')
    tasks.mockResolvedValue([task({ id: 't3', type: 'XMI_EXPORT', title: 'Exportar XMI' })])
    renderApp('/diagrams/d1/code')

    expect(await screen.findByText(/Todavía no se generó código/)).toBeInTheDocument()
    expect(screen.queryByText('Exportar XMI')).not.toBeInTheDocument()
  })
})

describe('CU-15 Descargar código generado', () => {
  beforeEach(() => diagramQuery.mockResolvedValue(diagram))
  afterEach(() => {
    useAuthStore.getState().logout()
    vi.resetAllMocks()
  })

  it('el DEVELOPER descarga el ZIP de una tarea completada', async () => {
    signIn('DEVELOPER')
    tasks.mockResolvedValue([task()])
    generations.mockResolvedValue([generation])
    download.mockResolvedValue()
    renderApp('/diagrams/d1/code')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Descargar ZIP' }))

    await waitFor(() => expect(download).toHaveBeenCalled())
    expect(download.mock.calls[0][0]).toBe('t1')
  })

  it('lista los archivos de cada generación', async () => {
    signIn('DEVELOPER')
    tasks.mockResolvedValue([task()])
    generations.mockResolvedValue([generation])
    renderApp('/diagrams/d1/code')

    expect(await screen.findByText('src/main/java/com/generated/app/model/Cliente.java')).toBeInTheDocument()
    expect(screen.getByText('pom.xml')).toBeInTheDocument()
  })

  it('una tarea fallida no se puede descargar', async () => {
    signIn('DEVELOPER')
    tasks.mockResolvedValue([task({ status: 'FAILED' })])
    generations.mockResolvedValue([])
    renderApp('/diagrams/d1/code')

    await screen.findByText('Fallida')
    expect(screen.queryByRole('button', { name: 'Descargar ZIP' })).not.toBeInTheDocument()
  })

  it('el DESIGNER no ve la descarga: el historial es del DEVELOPER', async () => {
    signIn('DESIGNER')
    tasks.mockResolvedValue([task()])
    renderApp('/diagrams/d1/code')

    await screen.findByText('Completada')
    expect(screen.queryByRole('button', { name: 'Descargar ZIP' })).not.toBeInTheDocument()
    expect(generations).not.toHaveBeenCalled()
  })
})

describe('nombre del archivo descargado', () => {
  it('toma el que propone el servidor', () => {
    expect(fileNameFrom('attachment; filename="codigo-dominio.zip"', 'x.zip')).toBe('codigo-dominio.zip')
  })

  it('entiende el formato con codificación', () => {
    expect(fileNameFrom("attachment; filename*=UTF-8''c%C3%B3digo.zip", 'x.zip')).toBe('código.zip')
  })

  it('usa el nombre de reserva si la cabecera falta', () => {
    expect(fileNameFrom(undefined, 'codigo-t1.zip')).toBe('codigo-t1.zip')
  })
})
