import axios, { AxiosError } from 'axios'
import { useAuthStore } from '@/stores/auth-store'
import { apiErrorFromBody, networkError } from './errors'

/** Cliente REST. Mismo origen que la SPA: en desarrollo lo proxifica Vite y en producción, Nginx. */
export const http = axios.create({
  baseURL: '/api',
  headers: { 'Content-Type': 'application/json' },
})

http.interceptors.request.use((config) => {
  const token = useAuthStore.getState().token
  if (token) config.headers.set('Authorization', `Bearer ${token}`)
  return config
})

const LOGIN_URL = '/auth/login'

http.interceptors.response.use(
  (response) => response,
  (error: unknown): Promise<never> => {
    if (!(error instanceof AxiosError)) return Promise.reject(error)
    if (!error.response) return Promise.reject(networkError())

    const { status, data } = error.response
    // Un 401 en el login es «credenciales incorrectas», no una sesión caducada.
    if (status === 401 && error.config?.url !== LOGIN_URL) useAuthStore.getState().logout()
    return Promise.reject(apiErrorFromBody(data, status))
  },
)
