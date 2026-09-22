import { expect, test, type Page } from '@playwright/test'

/**
 * Modo offline del Copilot en el navegador (CU-18 en la web), de punta a punta y cortando la red de verdad:
 * se escribe una instrucción sin conexión, se guarda en IndexedDB y, al volver la conexión, se envía sola.
 * Requiere el stack levantado (con el asistente de IA configurado) y `E2E_PASSWORD`.
 */
const PASSWORD = process.env.E2E_PASSWORD ?? ''

test.skip(!PASSWORD, 'Defina E2E_PASSWORD con la contraseña de los usuarios de ejemplo.')

async function login(page: Page) {
  await page.goto('/login')
  await page.getByLabel('Empresa').fill('demo')
  await page.getByLabel('Usuario').fill('designer')
  await page.getByLabel('Contraseña', { exact: true }).fill(PASSWORD)
  await page.getByRole('button', { name: 'Ingresar' }).click()
  await expect(page.getByRole('heading', { name: 'Proyectos' })).toBeVisible()
}

/** Deja un proyecto con un diagrama vacío abierto en el editor, con el panel del asistente a la vista. */
async function abrirAsistente(page: Page, suffix: string) {
  await page.getByRole('button', { name: 'Nuevo proyecto' }).click()
  await page.getByLabel('Nombre').fill(`Offline ${suffix}`)
  await page.getByRole('button', { name: 'Crear' }).click()
  await expect(page.getByRole('dialog')).toBeHidden()

  await page.getByRole('link', { name: `Offline ${suffix}` }).click()
  await page.waitForURL(/\/projects\/[0-9a-f-]{36}$/)
  await page.getByRole('button', { name: 'Nuevo diagrama' }).click()
  await page.getByLabel('Nombre').fill(`Diag ${suffix}`)
  await page.getByRole('button', { name: 'Crear' }).click()
  await expect(page.getByRole('button', { name: 'Agregar clase' })).toBeVisible()

  await page.getByRole('button', { name: 'Asistente' }).click()
  await expect(page.getByLabel('Instrucción para el asistente')).toBeVisible()
}

async function escribir(page: Page, texto: string) {
  await page.getByLabel('Instrucción para el asistente').fill(texto)
  await page.getByRole('button', { name: 'Enviar instrucción' }).click()
}

test('sin internet la instrucción se guarda en el navegador y se envía sola al volver', async ({ page, context }) => {
  const suffix = Date.now().toString().slice(-6)
  await login(page)
  await abrirAsistente(page, suffix)

  // --- Se va el internet ---
  await context.setOffline(true)
  await expect(page.getByText(/Sin conexión\. Las instrucciones que escriba se guardarán en este navegador/)).toBeVisible()

  await escribir(page, 'agrega una clase Cliente con nombre y email')
  await expect(page.getByText(/quedó guardada en este navegador y se enviará cuando vuelva la conexión/)).toBeVisible()
  await expect(page.getByText('Instrucciones en este navegador')).toBeVisible()
  await expect(page.getByText(/Pendiente de envío/)).toBeVisible()

  // --- Vuelve el internet: se envía sola, sin tocar nada ---
  await context.setOffline(false)
  await expect(page.getByText(/Enviada: /)).toBeVisible({ timeout: 60_000 })
  await expect(page.getByText(/Pendiente de envío/)).toBeHidden()

  // El asistente aplicó los cambios y el editor los releyó: la clase está en el lienzo.
  await expect(page.getByText('Cliente', { exact: true }).first()).toBeVisible({ timeout: 30_000 })
})

test('lo guardado sobrevive a recargar la página (IndexedDB) y se envía a mano', async ({ page }) => {
  const suffix = Date.now().toString().slice(-6)
  await login(page)
  await abrirAsistente(page, suffix)

  // La red del sistema sigue viva, pero el envío al asistente falla: como una wifi sin salida a internet. Solo se
  // bloquea esa petición para que el editor pueda seguir cargando el diagrama.
  const bloquearEnvio = (route: import('@playwright/test').Route) =>
    route.request().postData()?.includes('SendAiInstruction') ? route.abort('connectionrefused') : route.continue()
  await page.route('**/graphql', bloquearEnvio)

  await escribir(page, 'agrega una clase Pedido con total y fecha')
  await expect(page.getByText(/No se pudo enviar ahora\./)).toBeVisible()
  await expect(page.getByText(/Pendiente de envío · intento 1/)).toBeVisible()

  // Se recarga la página: la instrucción se leyó de IndexedDB, no de la memoria.
  await page.reload()
  await page.getByRole('button', { name: 'Asistente' }).click()
  await expect(page.getByText('agrega una clase Pedido con total y fecha')).toBeVisible()
  await expect(page.getByText(/Pendiente de envío/)).toBeVisible()

  // Vuelve el servidor: «Sincronizar ahora» la envía.
  await page.unroute('**/graphql', bloquearEnvio)
  await page.getByRole('button', { name: 'Sincronizar ahora' }).click()
  await expect(page.getByText(/Enviada: /)).toBeVisible({ timeout: 60_000 })
})
