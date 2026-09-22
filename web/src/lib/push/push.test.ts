import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { updateFcmToken } from '@/lib/api/notifications'
import { deleteToken, getToken, isSupported, onMessage } from 'firebase/messaging'
import { disablePush, enablePush, getPushStatus, listenForeground, PushError, refreshPushToken } from './push'

vi.mock('firebase/app', () => ({
  getApps: vi.fn(() => []),
  getApp: vi.fn(),
  initializeApp: vi.fn(() => ({})),
}))
vi.mock('firebase/messaging', () => ({
  isSupported: vi.fn(async () => true),
  getMessaging: vi.fn(() => ({})),
  getToken: vi.fn(async () => 'token-123'),
  deleteToken: vi.fn(async () => true),
  onMessage: vi.fn(() => () => {}),
}))
vi.mock('@/lib/api/notifications', () => ({ updateFcmToken: vi.fn(async () => {}) }))

const TOKEN_KEY = 'diagramas.fcmToken'
const register = vi.fn(async () => ({}))
const requestPermission = vi.fn(async () => 'granted')

function configure(vapidKey = 'vapid-publica') {
  vi.stubEnv('VITE_FIREBASE_API_KEY', 'api-key')
  vi.stubEnv('VITE_FIREBASE_PROJECT_ID', 'proyecto')
  vi.stubEnv('VITE_FIREBASE_MESSAGING_SENDER_ID', '123')
  vi.stubEnv('VITE_FIREBASE_APP_ID', '1:123:web:abc')
  vi.stubEnv('VITE_FIREBASE_VAPID_KEY', vapidKey)
}

function setPermission(permission: NotificationPermission) {
  vi.stubGlobal('Notification', { permission, requestPermission })
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(isSupported).mockResolvedValue(true)
  vi.mocked(getToken).mockResolvedValue('token-123')
  requestPermission.mockResolvedValue('granted')
  localStorage.clear()
  // jsdom no implementa service workers.
  Object.defineProperty(navigator, 'serviceWorker', {
    configurable: true,
    value: { register, ready: Promise.resolve({ scope: '/' }) },
  })
  setPermission('default')
  vi.stubEnv('VITE_FIREBASE_API_KEY', '')
  vi.stubEnv('VITE_FIREBASE_PROJECT_ID', '')
  vi.stubEnv('VITE_FIREBASE_MESSAGING_SENDER_ID', '')
  vi.stubEnv('VITE_FIREBASE_APP_ID', '')
  vi.stubEnv('VITE_FIREBASE_VAPID_KEY', '')
})

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

describe('sin configuración de Firebase', () => {
  it('queda desactivado y no toca Firebase ni el backend', async () => {
    expect(await getPushStatus()).toBe('not-configured')
    await expect(enablePush()).rejects.toMatchObject({ reason: 'not-configured' })
    expect(await refreshPushToken()).toBe(false)
    expect(await listenForeground(() => {})).toBeTypeOf('function')
    expect(getToken).not.toHaveBeenCalled()
    expect(updateFcmToken).not.toHaveBeenCalled()
  })

  it('basta con que falte un dato imprescindible', async () => {
    configure()
    vi.stubEnv('VITE_FIREBASE_APP_ID', '')
    expect(await getPushStatus()).toBe('not-configured')
  })
})

describe('estado', () => {
  it('un navegador sin soporte lo indica', async () => {
    configure()
    vi.mocked(isSupported).mockResolvedValue(false)
    expect(await getPushStatus()).toBe('unsupported')
  })

  it('permiso denegado', async () => {
    configure()
    setPermission('denied')
    expect(await getPushStatus()).toBe('denied')
  })

  it('sin permiso todavía ofrece activarlo', async () => {
    configure()
    expect(await getPushStatus()).toBe('prompt')
  })

  it('con permiso pero sin token guardado sigue ofreciendo activarlo', async () => {
    configure()
    setPermission('granted')
    expect(await getPushStatus()).toBe('prompt')
  })

  it('con permiso y token guardado está activo', async () => {
    configure()
    setPermission('granted')
    localStorage.setItem(TOKEN_KEY, 'token-123')
    expect(await getPushStatus()).toBe('enabled')
  })
})

describe('activar', () => {
  it('pide permiso, registra el service worker con la configuración y entrega el token al backend', async () => {
    configure()

    expect(await enablePush()).toBe('token-123')

    const swUrl = new URL((register.mock.calls[0] as unknown as [string])[0], 'http://localhost')
    expect(swUrl.pathname).toBe('/firebase-messaging-sw.js')
    expect(swUrl.searchParams.get('projectId')).toBe('proyecto')
    expect(swUrl.searchParams.get('messagingSenderId')).toBe('123')
    expect(getToken).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ vapidKey: 'vapid-publica', serviceWorkerRegistration: { scope: '/' } }),
    )
    expect(updateFcmToken).toHaveBeenCalledWith('token-123')
    expect(localStorage.getItem(TOKEN_KEY)).toBe('token-123')
  })

  it('funciona sin clave VAPID (queda a criterio de Firebase)', async () => {
    configure('')
    await enablePush()
    expect(getToken).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ vapidKey: undefined }))
  })

  it('si el usuario deniega el permiso no registra nada', async () => {
    configure()
    requestPermission.mockResolvedValue('denied')

    await expect(enablePush()).rejects.toMatchObject({ reason: 'denied' })

    expect(getToken).not.toHaveBeenCalled()
    expect(updateFcmToken).not.toHaveBeenCalled()
  })

  it('un navegador sin soporte no llega a pedir permiso', async () => {
    configure()
    vi.mocked(isSupported).mockResolvedValue(false)

    await expect(enablePush()).rejects.toMatchObject({ reason: 'unsupported' })
    expect(requestPermission).not.toHaveBeenCalled()
  })

  it('un fallo de Firebase se explica con su código y no deja token guardado', async () => {
    configure()
    vi.mocked(getToken).mockRejectedValue(Object.assign(new Error('x'), { code: 'messaging/token-subscribe-failed' }))

    const error = await enablePush().catch((e: unknown) => e)

    expect(error).toBeInstanceOf(PushError)
    expect((error as PushError).reason).toBe('failed')
    expect((error as PushError).message).toContain('messaging/token-subscribe-failed')
    expect(updateFcmToken).not.toHaveBeenCalled()
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
  })

  it('si el backend rechaza el token no se da por activado', async () => {
    configure()
    vi.mocked(updateFcmToken).mockRejectedValueOnce(new Error('503'))

    await expect(enablePush()).rejects.toMatchObject({ reason: 'failed' })
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
  })
})

describe('renovar al iniciar sesión', () => {
  it('sin permiso previo no molesta al usuario', async () => {
    configure()
    expect(await refreshPushToken()).toBe(false)
    expect(requestPermission).not.toHaveBeenCalled()
    expect(getToken).not.toHaveBeenCalled()
  })

  it('con permiso previo registra el token sin volver a preguntar', async () => {
    configure()
    setPermission('granted')

    expect(await refreshPushToken()).toBe(true)

    expect(requestPermission).not.toHaveBeenCalled()
    expect(updateFcmToken).toHaveBeenCalledWith('token-123')
  })

  it('nunca lanza: es un extra', async () => {
    configure()
    setPermission('granted')
    vi.mocked(getToken).mockRejectedValue(new Error('sin red'))
    await expect(refreshPushToken()).resolves.toBe(false)
  })
})

describe('desactivar', () => {
  it('invalida el token en Firebase y olvida el guardado', async () => {
    configure()
    localStorage.setItem(TOKEN_KEY, 'token-123')

    await disablePush()

    expect(deleteToken).toHaveBeenCalledOnce()
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
  })

  it('sin token guardado no llama a Firebase', async () => {
    configure()
    await disablePush()
    expect(deleteToken).not.toHaveBeenCalled()
  })

  it('aunque Firebase falle, descarta el token local', async () => {
    configure()
    localStorage.setItem(TOKEN_KEY, 'token-123')
    vi.mocked(deleteToken).mockRejectedValue(new Error('sin red'))

    await expect(disablePush()).resolves.toBeUndefined()
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
  })
})

describe('avisos con la pestaña en primer plano', () => {
  it('entrega título y cuerpo, y devuelve cómo cancelar', async () => {
    configure()
    const cancel = vi.fn()
    vi.mocked(onMessage).mockImplementation(((_m: unknown, next: (p: unknown) => void) => {
      next({ notification: { title: 'Código listo', body: 'Ya puede descargarlo' } })
      return cancel
    }) as never)
    const handler = vi.fn()

    const unsubscribe = await listenForeground(handler)

    expect(handler).toHaveBeenCalledWith({ title: 'Código listo', body: 'Ya puede descargarlo' })
    expect(unsubscribe).toBe(cancel)
  })

  it('un mensaje sin notification recibe un título por defecto', async () => {
    configure()
    vi.mocked(onMessage).mockImplementation(((_m: unknown, next: (p: unknown) => void) => {
      next({})
      return () => {}
    }) as never)
    const handler = vi.fn()

    await listenForeground(handler)

    expect(handler).toHaveBeenCalledWith({ title: 'Nueva notificación', body: '' })
  })
})
