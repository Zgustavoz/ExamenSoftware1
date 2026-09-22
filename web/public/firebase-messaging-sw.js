/*
 * Service worker de Firebase Cloud Messaging (push web).
 *
 * Un service worker no puede leer las variables de Vite, así que la aplicación lo registra pasándole la
 * configuración pública de Firebase en la query string (ver src/lib/push/push.ts). Esa configuración
 * (apiKey, projectId...) identifica el proyecto pero no es un secreto.
 *
 * Los mensajes que llegan con la pestaña cerrada o en segundo plano y que traen «notification» los muestra
 * el propio SDK; aquí solo hay que inicializarlo.
 */
importScripts('https://www.gstatic.com/firebasejs/12.19.0/firebase-app-compat.js')
importScripts('https://www.gstatic.com/firebasejs/12.19.0/firebase-messaging-compat.js')

const params = new URL(self.location.href).searchParams

firebase.initializeApp({
  apiKey: params.get('apiKey'),
  projectId: params.get('projectId'),
  messagingSenderId: params.get('messagingSenderId'),
  appId: params.get('appId'),
})

firebase.messaging()
