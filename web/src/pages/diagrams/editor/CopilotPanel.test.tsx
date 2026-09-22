import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { confirmAiChanges, listAiChats, sendAiInstruction, type AiResult } from '@/lib/api/copilot'
import type { Diagram } from '@/lib/api/diagrams'
import { ApiError } from '@/lib/api/errors'
import type { ClassContent } from '@/lib/diagram/types'
import { CopilotPanel } from './CopilotPanel'

vi.mock('@/lib/api/copilot', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/copilot')>()),
  listAiChats: vi.fn(),
  sendAiInstruction: vi.fn(),
  confirmAiChanges: vi.fn(),
}))

const chats = vi.mocked(listAiChats)
const ask = vi.mocked(sendAiInstruction)
const confirm = vi.mocked(confirmAiChanges)

const content: ClassContent = { schemaVersion: 1, type: 'CLASS', classes: [], relationships: [] }

const diagram: Diagram = {
  id: 'd1',
  projectId: 'p1',
  name: 'Dominio',
  description: null,
  type: 'CLASS',
  contentJson: content,
  version: 6,
  sourceDiagramId: null,
  createdBy: 'u1',
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-05T10:00:00Z',
}

function result(explanation: string): AiResult {
  return { explanation, operations: [{ op: 'ADD_CLASS', class: { name: 'Cliente' } }], diagram }
}

function renderPanel() {
  const applied = vi.fn()
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <CopilotPanel diagramId="d1" onApplied={applied} />
    </QueryClientProvider>,
  )
  return { applied, user: userEvent.setup() }
}

describe('CU-12 Generar o modificar diagrama con IA', () => {
  beforeEach(() => chats.mockResolvedValue([]))
  afterEach(() => vi.resetAllMocks())

  it('envía la instrucción como TEXTO y muestra la explicación (CP-03)', async () => {
    ask.mockResolvedValue(result('Agregué la clase Cliente con nombre y email'))
    const { applied, user } = renderPanel()

    await user.type(
      await screen.findByLabelText('Instrucción para el asistente'),
      'agrega una clase Cliente con nombre y email',
    )
    await user.click(screen.getByRole('button', { name: 'Enviar instrucción' }))

    await waitFor(() => expect(ask).toHaveBeenCalled())
    expect(ask.mock.calls[0][0]).toEqual({
      diagramId: 'd1',
      instruction: 'agrega una clase Cliente con nombre y email',
      inputType: 'TEXTO',
    })
    expect(await screen.findByText('Agregué la clase Cliente con nombre y email')).toBeInTheDocument()
    // Los cambios ya están aplicados y persistidos (D-07): el editor tiene que releer el diagrama.
    expect(applied).toHaveBeenCalled()
  })

  it('no envía una instrucción vacía', async () => {
    renderPanel()
    await screen.findByLabelText('Instrucción para el asistente')

    expect(screen.getByRole('button', { name: 'Enviar instrucción' })).toBeDisabled()
    expect(ask).not.toHaveBeenCalled()
  })

  it.each([
    ['AI_TIMEOUT', 504, 'El asistente tardó demasiado en responder.'],
    ['AI_UNAVAILABLE', 503, 'El asistente no está disponible en este momento.'],
    ['AI_INVALID_RESPONSE', 502, 'El asistente devolvió una respuesta que no se pudo usar.'],
  ] as const)('ante %s avisa sin tocar el diagrama (CP-04)', async (code, status, message) => {
    ask.mockRejectedValue(new ApiError(code, message, status))
    const { applied, user } = renderPanel()

    await user.type(await screen.findByLabelText('Instrucción para el asistente'), 'haz algo')
    await user.click(screen.getByRole('button', { name: 'Enviar instrucción' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(applied).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: /Confirmar cambios/ })).not.toBeInTheDocument()
  })

  it('confirmar los cambios llama a la mutación idempotente', async () => {
    ask.mockResolvedValue(result('Listo'))
    confirm.mockResolvedValue(diagram)
    const { user } = renderPanel()

    await user.type(await screen.findByLabelText('Instrucción para el asistente'), 'algo')
    await user.click(screen.getByRole('button', { name: 'Enviar instrucción' }))
    await user.click(await screen.findByRole('button', { name: /Confirmar cambios/ }))

    await waitFor(() => expect(confirm).toHaveBeenCalledWith('d1'))
  })
})

describe('CU-13 Consultar historial de conversación IA', () => {
  afterEach(() => vi.resetAllMocks())

  it('muestra los mensajes previos en orden', async () => {
    chats.mockResolvedValue([
      {
        id: 'chat1',
        diagramId: 'd1',
        title: null,
        messages: [
          { role: 'user', content: 'agrega Cliente', timestamp: '2026-03-01T10:00:00Z' },
          { role: 'assistant', content: 'Agregué la clase Cliente', timestamp: '2026-03-01T10:00:05Z' },
        ],
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:05Z',
      },
    ])
    renderPanel()

    expect(await screen.findByText('agrega Cliente')).toBeInTheDocument()
    expect(screen.getByText('Agregué la clase Cliente')).toBeInTheDocument()
  })

  it('una conversación vacía invita a empezar, sin error', async () => {
    chats.mockResolvedValue([])
    renderPanel()

    expect(await screen.findByText(/Pídale al asistente lo que necesite/)).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
