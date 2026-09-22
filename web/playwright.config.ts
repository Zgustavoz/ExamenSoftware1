import { defineConfig, devices } from '@playwright/test'

/**
 * E2E contra el sistema desplegado, igual que se ejecutaron las pruebas de aceptación del documento.
 * Necesita el stack levantado (`docker compose … up -d`) y la contraseña de los usuarios de ejemplo:
 *
 *   $env:E2E_PASSWORD = (Select-String -Path ..\.env -Pattern "^SEED_ADMIN_PASSWORD=").Line.Split('=')[1]
 *   npm run e2e
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8081',
    locale: 'es-ES',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
