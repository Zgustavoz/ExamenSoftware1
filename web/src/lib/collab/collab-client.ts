import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'
import type { DiagramOperation } from '@/lib/diagram/operations'
import { apiErrorFromBody, type ApiError } from '@/lib/api/errors'

export interface Participant {
  userId: string
  username: string
}

/** Mensaje difundido en `/topic/diagram.{id}` (sección 10.5). */
export interface CollabMessage {
  type: 'OP' | 'LOCK' | 'UNLOCK' | 'JOIN' | 'LEAVE' | 'ERROR'
  diagramId: string
  userId: string
  username: string
  elementId?: string | null
  op?: DiagramOperation | null
  version?: number | null
  ts: number
  participants?: Participant[] | null
}

export interface CollabHandlers {
  onMessage: (message: CollabMessage) => void
  onError: (error: ApiError) => void
  onConnectedChange: (connected: boolean) => void
}

/** Dirección del WebSocket: mismo origen que la SPA, con `ws`/`wss` según el esquema. */
export function websocketUrl(): string {
  const { protocol, host } = window.location
  return `${protocol === 'https:' ? 'wss:' : 'ws:'}//${host}/ws`
}

/**
 * Sesión de colaboración sobre un diagrama (CU-17). El JWT viaja en la cabecera `Authorization` del frame
 * CONNECT, y la reconexión es automática con espera creciente.
 */
export class CollabSession {
  private readonly client: Client
  private readonly diagramId: string
  private readonly handlers: CollabHandlers
  private subscriptions: StompSubscription[] = []

  constructor(diagramId: string, token: string, handlers: CollabHandlers) {
    this.diagramId = diagramId
    this.handlers = handlers
    this.client = new Client({
      brokerURL: websocketUrl(),
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 2000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        this.subscribe()
        this.send('join', {})
        this.handlers.onConnectedChange(true)
      },
      onWebSocketClose: () => this.handlers.onConnectedChange(false),
      onStompError: (frame) => this.handlers.onError(apiErrorFromBody(parse(frame.body), null)),
    })
  }

  activate() {
    this.client.activate()
  }

  /** Cierra la sesión avisando al resto de participantes. */
  async deactivate() {
    if (this.client.connected) this.send('leave', {})
    for (const subscription of this.subscriptions) subscription.unsubscribe()
    this.subscriptions = []
    await this.client.deactivate()
  }

  get connected(): boolean {
    return this.client.connected
  }

  sendOperation(operation: DiagramOperation) {
    this.send('op', { op: operation })
  }

  lock(elementId: string) {
    this.send('lock', { elementId })
  }

  unlock(elementId: string) {
    this.send('unlock', { elementId })
  }

  private subscribe() {
    this.subscriptions = [
      this.client.subscribe(`/topic/diagram.${this.diagramId}`, (message: IMessage) => {
        const body = parse(message.body)
        if (body) this.handlers.onMessage(body as CollabMessage)
      }),
      // Los errores del emisor llegan solo a él, con el formato de la sección 7.1.
      this.client.subscribe('/user/queue/errors', (message: IMessage) => {
        this.handlers.onError(apiErrorFromBody(parse(message.body), null))
      }),
    ]
  }

  private send(action: string, body: unknown) {
    if (!this.client.connected) return
    this.client.publish({ destination: `/app/diagram/${this.diagramId}/${action}`, body: JSON.stringify(body) })
  }
}

function parse(body: string): unknown {
  try {
    return JSON.parse(body)
  } catch {
    return null
  }
}
