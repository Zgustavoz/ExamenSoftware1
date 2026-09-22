/** Configuración web de Firebase, tomada de las variables `VITE_FIREBASE_*` del `.env` de la raíz. */
export interface FirebaseWebConfig {
  apiKey: string
  authDomain?: string
  projectId: string
  storageBucket?: string
  messagingSenderId: string
  appId: string
}

export interface PushConfig {
  firebase: FirebaseWebConfig
  /** Clave pública VAPID (Cloud Messaging → Certificados push web). Chrome puede exigirla. */
  vapidKey?: string
}

/**
 * Se lee en cada llamada (no al cargar el módulo) para poder cambiarla en las pruebas.
 * Devuelve `null` si falta alguno de los datos imprescindibles: en ese caso el push queda desactivado y
 * el resto de la aplicación funciona igual.
 */
export function pushConfig(): PushConfig | null {
  const env = import.meta.env
  const apiKey = env.VITE_FIREBASE_API_KEY
  const projectId = env.VITE_FIREBASE_PROJECT_ID
  const messagingSenderId = env.VITE_FIREBASE_MESSAGING_SENDER_ID
  const appId = env.VITE_FIREBASE_APP_ID
  if (!apiKey || !projectId || !messagingSenderId || !appId) return null

  return {
    firebase: {
      apiKey,
      projectId,
      messagingSenderId,
      appId,
      authDomain: env.VITE_FIREBASE_AUTH_DOMAIN || undefined,
      storageBucket: env.VITE_FIREBASE_STORAGE_BUCKET || undefined,
    },
    vapidKey: env.VITE_FIREBASE_VAPID_KEY || undefined,
  }
}
