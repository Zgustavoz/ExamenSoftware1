import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { listDiagrams, type Diagram } from '@/lib/api/diagrams'
import { ApiError } from '@/lib/api/errors'
import { getProject, type Project } from '@/lib/api/projects'
import type { User } from '@/lib/api/types'
import { importXmi, xmiFileProblem, MAX_XMI_BYTES } from '@/lib/api/xmi'
import { EMPTY_CLASS_CONTENT } from '@/lib/diagram/types'
import { useAuthStore } from '@/stores/auth-store'
import { renderApp } from '@/test/render'

vi.mock('@/lib/api/xmi', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/xmi')>()),
  importXmi: vi.fn(),
}))
vi.mock('@/lib/api/diagrams', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/diagrams')>()),
  listDiagrams: vi.fn(),
}))
vi.mock('@/lib/api/projects', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/projects')>()),
  getProject: vi.fn(),
}))

const upload = vi.mocked(importXmi)
const diagrams = vi.mocked(listDiagrams)
const project = vi.mocked(getProject)

const ventas: Project = {
  id: 'p1',
  name: 'Ventas',
  description: null,
  ownerId: 'u1',
  ownerName: 'Ana',
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const imported: Diagram = {
  id: 'd9',
  projectId: 'p1',
  name: 'Importado',
  description: null,
  type: 'CLASS',
  contentJson: EMPTY_CLASS_CONTENT,
  version: 1,
  sourceDiagramId: null,
  createdBy: 'u1',
  createdAt: '2026-03-07T10:00:00Z',
  updatedAt: '2026-03-07T10:00:00Z',
}

function signInAsDesigner() {
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

function xmiFile(name = 'modelo.xmi', content = '<xmi:XMI/>') {
  return new File([content], name, { type: 'application/xml' })
}

describe('CU-16 Importar XMI', () => {
  beforeEach(() => {
    signInAsDesigner()
    project.mockResolvedValue(ventas)
    diagrams.mockResolvedValue([])
  })
  afterEach(() => {
    useAuthStore.getState().logout()
    vi.resetAllMocks()
  })

  it('importa el archivo y abre el diagrama creado', async () => {
    upload.mockResolvedValue(imported)
    const { router } = renderApp('/projects/p1')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Importar XMI' }))
    await user.upload(await screen.findByLabelText('Archivo'), xmiFile())
    await user.click(screen.getByRole('button', { name: 'Importar' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/diagrams/d9'))
    expect(upload.mock.calls[0][0]).toBe('p1')
  })

  it('un XMI inválido muestra el mensaje del backend sin cerrar el diálogo', async () => {
    upload.mockRejectedValue(new ApiError('XMI_INVALID', 'El archivo XMI no se pudo interpretar.', 422))
    renderApp('/projects/p1')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Importar XMI' }))
    await user.upload(await screen.findByLabelText('Archivo'), xmiFile())
    await user.click(screen.getByRole('button', { name: 'Importar' }))

    expect(await screen.findByText('El archivo XMI no se pudo interpretar.')).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('rechaza una extensión que el backend no acepta, sin subir nada', async () => {
    renderApp('/projects/p1')
    // `applyAccept: false` imita al usuario que elige «todos los archivos» en el diálogo del sistema.
    const user = userEvent.setup({ applyAccept: false })

    await user.click(await screen.findByRole('button', { name: 'Importar XMI' }))
    await user.upload(await screen.findByLabelText('Archivo'), new File(['x'], 'modelo.txt'))

    expect(await screen.findByText(/extensión .xmi o .xml/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Importar' })).toBeDisabled()
    expect(upload).not.toHaveBeenCalled()
  })

  it('el DEVELOPER no ve la importación', async () => {
    useAuthStore.getState().login('jwt', {
      id: 'u2',
      username: 'developer',
      email: 'dev@demo.com',
      fullName: null,
      roles: ['DEVELOPER'],
      companyId: 'c1',
      active: true,
      createdAt: '2026-01-01T00:00:00Z',
    })
    renderApp('/projects/p1')

    await screen.findByRole('heading', { name: 'Ventas' })
    expect(screen.queryByRole('button', { name: 'Importar XMI' })).not.toBeInTheDocument()
  })
})

describe('comprobación previa del archivo XMI', () => {
  it.each(['modelo.xmi', 'MODELO.XML'])('acepta «%s»', (name) => {
    expect(xmiFileProblem(new File(['<xmi/>'], name))).toBeNull()
  })

  it('rechaza otra extensión', () => {
    expect(xmiFileProblem(new File(['x'], 'modelo.json'))).toMatch(/extensión/)
  })

  it('rechaza un archivo vacío', () => {
    expect(xmiFileProblem(new File([], 'modelo.xmi'))).toMatch(/vacío/)
  })

  it('rechaza lo que supera los 5 MB que admite el backend', () => {
    const big = new File(['x'], 'modelo.xmi')
    Object.defineProperty(big, 'size', { value: MAX_XMI_BYTES + 1 })
    expect(xmiFileProblem(big)).toMatch(/5 MB/)
  })
})
