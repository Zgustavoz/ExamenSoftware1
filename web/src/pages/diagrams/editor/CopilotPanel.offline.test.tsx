import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Mock } from 'vitest'
import { listAiChats, type AiResult } from '@/lib/api/copilot'
import type { Diagram } from '@/lib/api/diagrams'
import { ApiError, NETWORK_ERROR_MESSAGE } from '@/lib/api/errors'
import { MemoryPendingStore } from '@/lib/offline/pending-store'
import { OfflineQueue, type Connectivity } from '@/lib/offline/queue'
import { CopilotPanel } from './CopilotPanel'

vi.mock('@/lib/api/copilot', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/copilot')>()),
  listAiChats: vi.fn(),
}))

/** Conectividad de prueba: se enciende y se apaga a voluntad. */
class FakeConnectivity implements Connectivity {
  online = true
  private listeners = new Set<(online: boolean) => void>()
  isOnline = () => this.online
  subscribe = (listener: (online: boolean) => void) => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }
  set(online: boolean) {
    this.online = online
    this.listeners.forEach((l) => l(online))
  }
}

const diagram = { id: 'd1' } as Diagram
const result = (explanation: string): AiResult => ({ explanation, operations: [], diagram })

/** El escenario que se probó a mano: se envía una instrucción, se va el internet, vuelve, y se envía sola. */
describe('Modo offline del Copilot (pantalla)', () => {
  let net: FakeConnectivity
  let queue: OfflineQueue
  let enviadas: string[]
  let fallo: (instruction: string) => Error | null
  let applied: Mock<() => void>

  async function montar() {
    net = new FakeConnectivity()
    enviadas = []
    fallo = () => null
    queue = new OfflineQueue({
      store: new MemoryPendingStore(),
      connectivity: net,
      retryEveryMs: null,
      send: async ({ instruction }) => {
        const error = fallo(instruction)
        if (error) throw error
        enviadas.push(instruction)
        return result(`Listo: ${instruction}`)
      },
    })
    queue.start()
    await queue.setOwner('c1/u1')

    applied = vi.fn()
    vi.mocked(listAiChats).mockResolvedValue([])
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <CopilotPanel diagramId="d1" onApplied={applied} queue={queue} />
      </QueryClientProvider>,
    )
    return userEvent.setup()
  }

  async function escribir(user: ReturnType<typeof userEvent.setup>, texto: string) {
    await user.type(screen.getByLabelText('Instrucción para el asistente'), texto)
    await user.click(screen.getByRole('button', { name: 'Enviar instrucción' }))
  }

  afterEach(() => {
    queue.stop()
    onlineManager.setOnline(true)
    vi.resetAllMocks()
  })

  it('con conexión no muestra ningún aviso de «sin conexión»', async () => {
    await montar()

    expect(screen.queryByText(/Sin conexión\./)).not.toBeInTheDocument()
  })

  it('al perder la conexión avisa', async () => {
    await montar()

    act(() => net.set(false))

    expect(await screen.findByText(/Sin conexión\. Las instrucciones que escriba se guardarán en este navegador/)).toBeInTheDocument()
  })

  it('sin conexión la instrucción se guarda en el navegador y no se envía', async () => {
    const user = await montar()
    act(() => net.set(false))

    await escribir(user, 'agrega una clase Cliente')

    expect(await screen.findByText(/quedó guardada en este navegador y se enviará cuando vuelva la conexión/)).toBeInTheDocument()
    expect(enviadas).toEqual([])
    expect(screen.getByText('Instrucciones en este navegador')).toBeInTheDocument()
    expect(screen.getByText('agrega una clase Cliente')).toBeInTheDocument()
    expect(screen.getByText(/Pendiente de envío/)).toBeInTheDocument()
    // El campo queda libre para escribir la siguiente sin volver a enviar esta.
    expect(screen.getByLabelText('Instrucción para el asistente')).toHaveValue('')
  })

  it('al volver la conexión se envía sola, pasa a «Enviada» y el editor relee el diagrama', async () => {
    const user = await montar()
    act(() => net.set(false))
    await escribir(user, 'agrega una clase Cliente')
    await screen.findByText(/Pendiente de envío/)

    act(() => net.set(true))

    expect(await screen.findByText(/Enviada: Listo: agrega una clase Cliente/)).toBeInTheDocument()
    expect(enviadas).toEqual(['agrega una clase Cliente'])
    expect(screen.queryByText(/Pendiente de envío/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Sin conexión\./)).not.toBeInTheDocument()
    // El asistente ya aplicó los cambios (D-07): el editor tiene que releerlos.
    await waitFor(() => expect(applied).toHaveBeenCalled())
  })

  it('aunque TanStack Query esté sin conexión, la instrucción se guarda en vez de quedarse «trabajando»', async () => {
    // Lo que hace el navegador de verdad al cortar la red. Por omisión TanStack pausa entonces las mutaciones y ni
    // las ejecuta; este fallo solo se veía en un navegador real, porque jsdom siempre dice que hay conexión.
    const user = await montar()
    act(() => {
      onlineManager.setOnline(false)
      net.set(false)
    })

    await escribir(user, 'agrega una clase Cliente')

    expect(await screen.findByText(/quedó guardada en este navegador/)).toBeInTheDocument()
    expect(screen.queryByText('El asistente está trabajando…')).not.toBeInTheDocument()
  })

  it('varias instrucciones escritas sin conexión se envían todas y en orden', async () => {
    const user = await montar()
    act(() => net.set(false))
    await escribir(user, 'primera')
    await escribir(user, 'segunda')

    act(() => net.set(true))

    await waitFor(() => expect(enviadas).toEqual(['primera', 'segunda']))
  })

  it('si el envío falla por la red, se guarda aunque el navegador dijera que había conexión', async () => {
    const user = await montar()
    fallo = () => new ApiError('NETWORK_ERROR', NETWORK_ERROR_MESSAGE)

    await escribir(user, 'agrega una clase Cliente')

    expect(await screen.findByText(/No se pudo enviar ahora\./)).toBeInTheDocument()
    expect(screen.getByText(/Pendiente de envío · intento 1/)).toBeInTheDocument()
  })

  it('un fallo del asistente se muestra como error y no se guarda (CP-04)', async () => {
    const user = await montar()
    fallo = () => new ApiError('AI_UNAVAILABLE', 'El asistente no está disponible en este momento. Su diagrama no fue modificado.')

    await escribir(user, 'agrega una clase Cliente')

    expect(await screen.findByText(/El asistente no está disponible en este momento/)).toBeInTheDocument()
    expect(screen.queryByText('Instrucciones en este navegador')).not.toBeInTheDocument()
  })

  it('se puede quitar una instrucción pendiente de la lista', async () => {
    const user = await montar()
    act(() => net.set(false))
    await escribir(user, 'no la quiero')
    await screen.findByText(/Pendiente de envío/)

    await user.click(screen.getByRole('button', { name: 'Quitar de la lista' }))

    await waitFor(() => expect(screen.queryByText('no la quiero')).not.toBeInTheDocument())
  })

  it('«Sincronizar ahora» reintenta a mano', async () => {
    const user = await montar()
    fallo = () => new ApiError('NETWORK_ERROR', NETWORK_ERROR_MESSAGE)
    await escribir(user, 'agrega una clase Cliente')
    await screen.findByText(/Pendiente de envío · intento 1/)

    fallo = () => null
    await user.click(screen.getByRole('button', { name: 'Sincronizar ahora' }))

    expect(await screen.findByText(/Enviada: Listo/)).toBeInTheDocument()
  })
})
