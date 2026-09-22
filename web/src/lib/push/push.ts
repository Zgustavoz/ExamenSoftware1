import { updateFcmToken } from '@/lib/api/notifications'
import { pushConfig, type PushConfig } from './config'

/**
 * Notificaciones push web (CU-19) con Firebase Cloud Messaging.
 *
 * El SDK de Firebase se carga con `import()` solo cuando hace falta, para que quien no active los avisos no
 * pague su peso en el paquete principal. Si faltan las variables `VITE_FIREBASE_*` todo queda desactivado y
 * el resto de la aplicación funciona igual.
 */

export type PushStatus = 'not-configured' | 'unsupported' | 'denied' | 'prompt' | 'enabled'

export type PushFailure = 'not-configured' | 'unsupported' | 'denied' | 'failed'

/** Fallo al activar el push, con el motivo para que la pantalla pueda explicarlo. */
export class PushError extends Error {
  readonly reason: PushFailure

  constructor(reason: PushFailure, message: string, options?: ErrorOptions) {
    super(message, options)
    this.name = 'PushError'
    this.reason = reason
  }
}

export interface ForegroundNotification {
  title: string
  body: string
}

const TOKEN_KEY = 'diagramas.fcmToken'
const SERVICE_WORKER_PATH = '/firebase-messaging-sw.js'

/** El service worker no lee variables de Vite: recibe la configuración pública en la query string. */
function serviceWorkerUrl(config: PushConfig): string {
  const { apiKey, projectId, messagingSenderId, appId } = config.firebase
  return `${SERVICE_WORKER_PATH}?${new URLSearchParams({ apiKey, projectId, messagingSenderId, appId })}`
}

async function loadMessaging(config: PushConfig) {
  const [{ getApp, getApps, initializeApp }, sdk] = await Promise.all([
    import('firebase/app'),
    import('firebase/messaging'),
  ])
  const app = getApps().length > 0 ? getApp() : initializeApp(config.firebase)
  return { messaging: sdk.getMessaging(app), sdk }
}

async function isPushSupported(): Promise<boolean> {
  if (typeof Notification === 'undefined' || !('serviceWorker' in navigator)) return false
  const { isSupported } = await import('firebase/messaging')
  return isSupported()
}

/** Estado actual, para que la pantalla ofrezca la acción adecuada. */
export async function getPushStatus(): Promise<PushStatus> {
  if (!pushConfig()) return 'not-configured'
  if (!(await isPushSupported())) return 'unsupported'
  if (Notification.permission === 'denied') return 'denied'
  if (Notification.permission === 'granted' && localStorage.getItem(TOKEN_KEY)) return 'enabled'
  return 'prompt'
}

/** Obtiene el token de este navegador y se lo entrega al backend (`PUT /api/me/fcm-token`). */
async function registerToken(config: PushConfig): Promise<string> {
  try {
    await navigator.serviceWorker.register(serviceWorkerUrl(config))
    const registration = await navigator.serviceWorker.ready
    const { messaging, sdk } = await loadMessaging(config)
    const token = await sdk.getToken(messaging, {
      vapidKey: config.vapidKey,
      serviceWorkerRegistration: registration,
    })
    if (!token) throw new Error('Firebase no devolvió ningún token.')
    await updateFcmToken(token)
    localStorage.setItem(TOKEN_KEY, token)
    return token
  } catch (cause) {
    if (cause instanceof PushError) throw cause
    const code = (cause as { code?: string }).code
    throw new PushError(
      'failed',
      `No se pudieron activar los avisos${code ? ` (${code})` : ''}. Revise la configuración de Firebase e inténtelo de nuevo.`,
      { cause },
    )
  }
}

/** Pide permiso al usuario y registra este navegador. Solo se llama desde un gesto del usuario (un clic). */
export async function enablePush(): Promise<string> {
  const config = pushConfig()
  if (!config) throw new PushError('not-configured', 'Los avisos push no están configurados en este entorno.')
  if (!(await isPushSupported())) throw new PushError('unsupported', 'Este navegador no admite avisos push.')

  const permission = await Notification.requestPermission()
  if (permission !== 'granted') {
    throw new PushError(
      'denied',
      'Los avisos están bloqueados. Puede permitirlos desde los ajustes del sitio en su navegador.',
    )
  }
  return registerToken(config)
}

/**
 * Al iniciar sesión, si el usuario ya había dado permiso, renueva el token sin volver a preguntar. Sirve para
 * que otra cuenta que entre en el mismo navegador reciba sus propios avisos. Nunca lanza: es un extra.
 */
export async function refreshPushToken(): Promise<boolean> {
  const config = pushConfig()
  if (!config || typeof Notification === 'undefined' || Notification.permission !== 'granted') return false
  try {
    if (!(await isPushSupported())) return false
    await registerToken(config)
    return true
  } catch {
    return false
  }
}

/**
 * Deja de recibir avisos en este navegador. Invalidar el token en Firebase hace que el backend reciba
 * `UNREGISTERED` en el siguiente envío y lo borre del usuario (CU-19), así que al cerrar sesión la cuenta
 * anterior no sigue recibiendo avisos aquí.
 */
export async function disablePush(): Promise<void> {
  const config = pushConfig()
  const hadToken = localStorage.getItem(TOKEN_KEY) !== null
  localStorage.removeItem(TOKEN_KEY)
  if (!config || !hadToken) return
  try {
    const { messaging, sdk } = await loadMessaging(config)
    await sdk.deleteToken(messaging)
  } catch {
    // Ya no hay nada más que hacer: el token local se descartó.
  }
}

/**
 * Con la pestaña abierta y en primer plano Firebase no muestra la notificación del sistema: la entrega
 * aquí para que la aplicación la muestre. Devuelve la función que cancela la suscripción.
 */
export async function listenForeground(handler: (notification: ForegroundNotification) => void): Promise<() => void> {
  const config = pushConfig()
  if (!config || !(await isPushSupported())) return () => {}
  const { messaging, sdk } = await loadMessaging(config)
  return sdk.onMessage(messaging, (payload) =>
    handler({
      title: payload.notification?.title ?? 'Nueva notificación',
      body: payload.notification?.body ?? '',
    }),
  )
}
