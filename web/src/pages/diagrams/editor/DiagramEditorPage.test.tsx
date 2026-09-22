import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { generateSequenceDiagram, getDiagram, saveDiagram, type Diagram } from '@/lib/api/diagrams'
import { exportXmi } from '@/lib/api/xmi'
import { ApiError } from '@/lib/api/errors'
import type { User } from '@/lib/api/types'
import { CollabSession, type CollabHandlers, type CollabMessage } from '@/lib/collab/collab-client'
import type { DiagramOperation } from '@/lib/diagram/operations'
import type { ClassContent } from '@/lib/diagram/types'
import { useAuthStore } from '@/stores/auth-store'
import { renderApp } from '@/test/render'
import { formatParameters, parseParameters } from '@/lib/diagram/parameters'

vi.mock('@/lib/api/diagrams', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/diagrams')>()),
  getDiagram: vi.fn(),
  saveDiagram: vi.fn(),
  generateSequenceDiagram: vi.fn(),
}))
vi.mock('@/lib/api/xmi', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/xmi')>()),
  exportXmi: vi.fn(),
}))
vi.mock('@/lib/collab/collab-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/collab/collab-client')>()),
  CollabSession: vi.fn(),
}))

const get = vi.mocked(getDiagram)
const save = vi.mocked(saveDiagram)
const sequence = vi.mocked(generateSequenceDiagram)
const exportar = vi.mocked(exportXmi)

/** Sesión de colaboración simulada: recoge lo enviado y deja empujar mensajes del servidor. */
class FakeSession {
  static current: FakeSession | null = null
  readonly sent: DiagramOperation[] = []
  readonly locked: string[] = []
  handlers: CollabHandlers
  connected = false

  constructor(_diagramId: string, _token: string, handlers: CollabHandlers) {
    this.handlers = handlers
    FakeSession.current = this
  }

  activate() {
    this.connected = true
    this.handlers.onConnectedChange(true)
  }

  async deactivate() {
    this.connected = false
  }

  sendOperation(operation: DiagramOperation) {
    this.sent.push(operation)
  }

  lock(elementId: string) {
    this.locked.push(elementId)
  }

  unlock() {}

  /** Simula la difusión del servidor tras aplicar una operación. */
  broadcast(operation: DiagramOperation, version: number) {
    this.handlers.onMessage({
      type: 'OP',
      diagramId: 'd1',
      userId: 'u9',
      username: 'otra',
      op: operation,
      version,
      ts: Date.now(),
    } satisfies CollabMessage)
  }

  emit(message: Partial<CollabMessage>) {
    this.handlers.onMessage({
      type: 'JOIN',
      diagramId: 'd1',
      userId: 'u9',
      username: 'otra',
      ts: Date.now(),
      ...message,
    } as CollabMessage)
  }
}

// La implementación tiene que ser construible: una clase, no una función flecha.
vi.mocked(CollabSession).mockImplementation(FakeSession as unknown as typeof CollabSession)

const content: ClassContent = {
  schemaVersion: 1,
  type: 'CLASS',
  classes: [
    {
      id: 'c1',
      name: 'Cliente',
      stereotype: null,
      visibility: 'PUBLIC',
      x: 100,
      y: 80,
      attributes: [{ id: 'a1', name: 'nombre', type: 'String', visibility: 'PRIVATE' }],
      methods: [{ id: 'm1', name: 'getNombre', returnType: 'String', visibility: 'PUBLIC', parameters: [] }],
    },
    {
      id: 'c2',
      name: 'Pedido',
      stereotype: null,
      visibility: 'PUBLIC',
      x: 420,
      y: 80,
      attributes: [],
      methods: [],
    },
  ],
  relationships: [
    {
      id: 'r1',
      type: 'ASSOCIATION',
      sourceId: 'c1',
      targetId: 'c2',
      sourceMultiplicity: '1',
      targetMultiplicity: '0..*',
    },
  ],
}

function diagram(overrides: Partial<Diagram> = {}): Diagram {
  return {
    id: 'd1',
    projectId: 'p1',
    name: 'Dominio',
    description: null,
    type: 'CLASS',
    contentJson: content,
    version: 5,
    sourceDiagramId: null,
    createdBy: 'u1',
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-05T10:00:00Z',
    ...overrides,
  }
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

/** Espera a que el editor esté montado y conectado. */
async function openEditor() {
  const result = renderApp('/diagrams/d1')
  await screen.findByRole('button', { name: 'Agregar clase' })
  return result
}

describe('CU-07 Editar diagrama manualmente', () => {
  beforeEach(() => {
    signInAsDesigner()
    get.mockResolvedValue(diagram())
  })
  afterEach(() => {
    useAuthStore.getState().logout()
    FakeSession.current = null
    vi.clearAllMocks()
  })

  it('carga el diagrama y muestra su versión y el estado de la conexión', async () => {
    await openEditor()

    expect(screen.getByText('versión 5')).toBeInTheDocument()
    expect(await screen.findByText('Conectado')).toBeInTheDocument()
    expect(screen.getByText('Cliente')).toBeInTheDocument()
  })

  it('agregar una clase envía ADD_CLASS, no toca el estado local hasta la respuesta', async () => {
    await openEditor()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Agregar clase' }))

    expect(FakeSession.current!.sent[0]).toMatchObject({ op: 'ADD_CLASS', class: { name: 'Clase3' } })
    expect(screen.queryByText('Clase3')).not.toBeInTheDocument()
  })

  it('el cambio aparece cuando el servidor lo difunde', async () => {
    await openEditor()

    FakeSession.current!.broadcast(
      {
        op: 'ADD_CLASS',
        class: {
          id: 'c3',
          name: 'Factura',
          stereotype: null,
          visibility: 'PUBLIC',
          x: 700,
          y: 80,
          attributes: [],
          methods: [],
        } as never,
      },
      6,
    )

    expect(await screen.findByText('Factura')).toBeInTheDocument()
    expect(screen.getByText('versión 6')).toBeInTheDocument()
  })

  it('si se pierde un mensaje intermedio, recarga el diagrama entero', async () => {
    await openEditor()
    get.mockClear()

    // Llega la versión 8 cuando la local es la 5: faltan mensajes.
    FakeSession.current!.broadcast({ op: 'REMOVE_CLASS', classId: 'c1' }, 8)

    await waitFor(() => expect(get).toHaveBeenCalled())
    expect(screen.getByText('Cliente')).toBeInTheDocument()
  })

  it('un ELEMENT_LOCKED del servidor llega como error al emisor', async () => {
    await openEditor()

    FakeSession.current!.handlers.onError(
      new ApiError('ELEMENT_LOCKED', 'El elemento está siendo editado por otro usuario.', 409),
    )

    // El diagrama no cambia: el servidor rechazó la operación antes de aplicarla.
    expect(screen.getByText('Cliente')).toBeInTheDocument()
    expect(screen.getByText('versión 5')).toBeInTheDocument()
  })

  it('marca como bloqueada la clase que otro usuario está editando', async () => {
    await openEditor()

    FakeSession.current!.emit({ type: 'LOCK', elementId: 'c1', userId: 'u9', username: 'otra' })

    expect(await screen.findByText(/otra está editando/)).toBeInTheDocument()
  })

  it('el bloqueo propio no marca nada: es el que uno mismo tomó', async () => {
    await openEditor()

    FakeSession.current!.emit({ type: 'LOCK', elementId: 'c1', userId: 'u1', username: 'designer' })

    expect(screen.queryByText(/está editando/)).not.toBeInTheDocument()
  })

  it('al liberarse el bloqueo la clase vuelve a quedar disponible', async () => {
    await openEditor()

    FakeSession.current!.emit({ type: 'LOCK', elementId: 'c1', userId: 'u9', username: 'otra' })
    await screen.findByText(/otra está editando/)

    FakeSession.current!.emit({ type: 'UNLOCK', elementId: 'c1', userId: 'u9', username: 'otra' })

    await waitFor(() => expect(screen.queryByText(/está editando/)).not.toBeInTheDocument())
  })

  it('muestra los participantes que van llegando', async () => {
    await openEditor()

    FakeSession.current!.emit({
      type: 'JOIN',
      participants: [
        { userId: 'u1', username: 'designer' },
        { userId: 'u9', username: 'otra' },
      ],
    })

    expect(await screen.findByText('designer, otra')).toBeInTheDocument()
  })
})

describe('CU-10 Guardar y versionar', () => {
  beforeEach(() => {
    signInAsDesigner()
    get.mockResolvedValue(diagram())
  })
  afterEach(() => {
    useAuthStore.getState().logout()
    FakeSession.current = null
    vi.clearAllMocks()
  })

  it('guardar envía el contenido con la versión que tenía el cliente', async () => {
    save.mockResolvedValue(diagram({ version: 6 }))
    await openEditor()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() => expect(save).toHaveBeenCalled())
    expect(save.mock.calls[0][0]).toMatchObject({ id: 'd1', baseVersion: 5 })
  })

  it('ante VERSION_CONFLICT adopta el estado que devuelve el servidor', async () => {
    const serverContent: ClassContent = {
      ...content,
      classes: [{ ...content.classes[0], name: 'ClienteDelServidor' }],
      relationships: [],
    }
    save.mockRejectedValue(
      new ApiError('VERSION_CONFLICT', 'El diagrama cambió.', 409, {
        currentVersion: 9,
        contentJson: serverContent,
      }),
    )
    await openEditor()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText('ClienteDelServidor')).toBeInTheDocument()
    expect(screen.getByText('versión 9')).toBeInTheDocument()
  })
})

describe('parámetros de un método', () => {
  it('«nombre: Tipo, otro: Tipo» va y vuelve sin perder nada', () => {
    const parsed = parseParameters('cliente: Cliente, total: BigDecimal')
    expect(parsed).toEqual([
      { name: 'cliente', type: 'Cliente' },
      { name: 'total', type: 'BigDecimal' },
    ])
    expect(formatParameters(parsed)).toBe('cliente: Cliente, total: BigDecimal')
  })

  it('tolera espacios, comas sobrantes y tipos ausentes', () => {
    expect(parseParameters('  a : int ,, b ')).toEqual([
      { name: 'a', type: 'int' },
      { name: 'b', type: 'String' },
    ])
    expect(parseParameters('')).toEqual([])
  })
})

describe('CU-20 Generar diagrama de secuencia desde clases', () => {
  beforeEach(() => {
    signInAsDesigner()
    get.mockResolvedValue(diagram())
  })
  afterEach(() => {
    useAuthStore.getState().logout()
    FakeSession.current = null
    vi.clearAllMocks()
  })

  it('genera la secuencia y la abre en el visor', async () => {
    sequence.mockResolvedValue(
      diagram({ id: 'seq1', type: 'SEQUENCE', sourceDiagramId: 'd1', name: 'Dominio (secuencia)' }),
    )
    const { router } = await openEditor()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /Secuencia/ }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/diagrams/seq1/view'))
    expect(sequence.mock.calls[0][0]).toBe('d1')
  })

  it('un fallo de la IA se avisa y el editor sigue en pie', async () => {
    sequence.mockRejectedValue(
      new ApiError('AI_UNAVAILABLE', 'El asistente no está disponible en este momento.', 503),
    )
    const { router } = await openEditor()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /Secuencia/ }))

    await waitFor(() => expect(sequence).toHaveBeenCalled())
    expect(router.state.location.pathname).toBe('/diagrams/d1')
    expect(screen.getByText('Cliente')).toBeInTheDocument()
  })
})

describe('CU-16 Exportar XMI desde el editor', () => {
  beforeEach(() => {
    signInAsDesigner()
    get.mockResolvedValue(diagram())
  })
  afterEach(() => {
    useAuthStore.getState().logout()
    FakeSession.current = null
    vi.clearAllMocks()
  })

  it('exporta con el nombre del diagrama', async () => {
    exportar.mockResolvedValue()
    await openEditor()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /XMI/ }))

    await waitFor(() => expect(exportar).toHaveBeenCalled())
    expect(exportar.mock.calls[0]).toEqual(['d1', 'Dominio'])
  })
})
