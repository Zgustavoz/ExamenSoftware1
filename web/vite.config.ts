import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import path from 'node:path'
import { defineConfig } from 'vitest/config'

// En desarrollo, Vite hace de proxy hacia el backend (mismo origen que en producción con Nginx,
// así no hay problemas de CORS). El destino se puede cambiar con VITE_BACKEND_URL.
const backend = process.env.VITE_BACKEND_URL ?? 'http://localhost:8080'

export default defineConfig({
  // Un solo .env para todo el repositorio. Vite solo expone al navegador las variables que empiezan por VITE_,
  // así que las contraseñas y claves del backend que hay en ese archivo no llegan al cliente.
  envDir: path.resolve(import.meta.dirname, '..'),
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': path.resolve(import.meta.dirname, 'src') },
  },
  server: {
    port: 4200,
    proxy: {
      '/api': backend,
      '/graphql': backend,
      '/ws': { target: backend, ws: true },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
    // Las pruebas de extremo a extremo las ejecuta Playwright, no Vitest.
    exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
    css: false,
    // Las pruebas montan la aplicación entera y sus rutas diferidas; los 5 s por defecto se quedan cortos
    // en la primera carga de cada pantalla.
    testTimeout: 20_000,
    // Un proceso por núcleo agota la memoria cuando el stack de Docker está levantado, que es lo normal
    // mientras se desarrolla: cada worker monta jsdom y la aplicación entera.
    maxWorkers: 4,
  },
})
