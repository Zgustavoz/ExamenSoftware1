import { gql } from './graphql'
import { useAuthStore } from '@/stores/auth-store'

function respond(status: number, body: unknown) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })),
  )
}

describe('cliente GraphQL', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    useAuthStore.getState().logout()
  })

  it('devuelve los datos', async () => {
    respond(200, { data: { diagram: { id: 'd1' } } })
    await expect(gql('{ diagram(id: "d1") { id } }')).resolves.toEqual({ diagram: { id: 'd1' } })
  })

  it('VERSION_CONFLICT llega como ApiError con el estado actual en details', async () => {
    const details = { currentVersion: 7, contentJson: { classes: [] } }
    respond(200, {
      data: null,
      errors: [{ message: 'El diagrama cambió.', extensions: { code: 'VERSION_CONFLICT', details } }],
    })
    await expect(gql('mutation { saveDiagram }')).rejects.toMatchObject({ code: 'VERSION_CONFLICT', details })
  })

  it('un 401 con formato REST cierra la sesión', async () => {
    useAuthStore.getState().login('t', {
      id: 'u', username: 'x', email: 'x@d.com', fullName: null, roles: ['DESIGNER'],
      companyId: 'c', active: true, createdAt: '2026-01-01T00:00:00Z',
    })
    respond(401, { code: 'UNAUTHORIZED', message: 'Debe iniciar sesión para continuar.', details: {} })
    await expect(gql('{ diagrams(projectId: "p") { id } }')).rejects.toMatchObject({ code: 'UNAUTHORIZED', status: 401 })
    expect(useAuthStore.getState().token).toBeNull()
  })

  it('un corte de red es NETWORK_ERROR', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Promise.reject(new TypeError('Failed to fetch'))))
    await expect(gql('{ diagrams(projectId: "p") { id } }')).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
  })
})
