import { AxiosError, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import type { User } from './types'
import { ApiError } from './errors'
import { http } from './http'
import { useAuthStore } from '@/stores/auth-store'

const user: User = {
  id: 'u1',
  username: 'designer',
  email: 'd@demo.com',
  fullName: null,
  roles: ['DESIGNER'],
  companyId: 'c1',
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
}

/** Adaptador que responde con el estado y cuerpo dados, o simula un corte de red si `status` es null. */
function reply(status: number | null, data: unknown = {}): AxiosAdapter {
  return (config: InternalAxiosRequestConfig) => {
    if (status === null) return Promise.reject(new AxiosError('Network Error', 'ERR_NETWORK', config))
    const response = { data, status, statusText: '', headers: {}, config }
    return status < 400
      ? Promise.resolve(response)
      : Promise.reject(new AxiosError('fail', 'ERR_BAD_REQUEST', config, null, response))
  }
}

describe('cliente REST', () => {
  beforeEach(() => useAuthStore.getState().login('token-123', user))
  afterEach(() => {
    delete http.defaults.adapter
    useAuthStore.getState().logout()
  })

  it('adjunta el JWT en la cabecera Authorization', async () => {
    let auth: unknown
    http.defaults.adapter = (config) => {
      auth = config.headers.get('Authorization')
      return reply(200)(config)
    }
    await http.get('/projects')
    expect(auth).toBe('Bearer token-123')
  })

  it('un 401 cierra la sesión y lanza ApiError', async () => {
    http.defaults.adapter = reply(401, { code: 'UNAUTHORIZED', message: 'Debe iniciar sesión para continuar.' })
    await expect(http.get('/projects')).rejects.toMatchObject({ code: 'UNAUTHORIZED', status: 401 })
    expect(useAuthStore.getState().token).toBeNull()
  })

  it('un 401 del login (credenciales incorrectas) NO cierra la sesión', async () => {
    http.defaults.adapter = reply(401, { code: 'INVALID_CREDENTIALS', message: 'Credenciales incorrectas.' })
    await expect(http.post('/auth/login', {})).rejects.toBeInstanceOf(ApiError)
    expect(useAuthStore.getState().token).toBe('token-123')
  })

  it('traduce un corte de red a NETWORK_ERROR', async () => {
    http.defaults.adapter = reply(null)
    await expect(http.get('/projects')).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
  })
})
