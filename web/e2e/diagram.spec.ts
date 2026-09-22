import { expect, test, type Page } from '@playwright/test'

/**
 * CP-01 y CP-02 como caja negra sobre el entorno desplegado (sección 12).
 * Requiere el stack levantado y `E2E_PASSWORD` con la contraseña de los usuarios de ejemplo.
 */
const PASSWORD = process.env.E2E_PASSWORD ?? ''

test.skip(!PASSWORD, 'Defina E2E_PASSWORD con la contraseña de los usuarios de ejemplo.')

async function login(page: Page, username = 'designer') {
  await page.goto('/login')
  await page.getByLabel('Empresa').fill('demo')
  await page.getByLabel('Usuario').fill(username)
  await page.getByLabel('Contraseña', { exact: true }).fill(PASSWORD)
  await page.getByRole('button', { name: 'Ingresar' }).click()
  await expect(page.getByRole('heading', { name: 'Proyectos' })).toBeVisible()
}

/** Crea un proyecto y un diagrama nuevos, y deja abierto el editor. */
async function newDiagram(page: Page): Promise<string> {
  const suffix = Date.now().toString().slice(-6)

  await page.getByRole('button', { name: 'Nuevo proyecto' }).click()
  await page.getByLabel('Nombre').fill(`E2E ${suffix}`)
  await page.getByRole('button', { name: 'Crear' }).click()
  await expect(page.getByRole('dialog')).toBeHidden()

  await page.getByRole('link', { name: `E2E ${suffix}` }).click()
  await page.getByRole('button', { name: 'Nuevo diagrama' }).click()
  await page.getByLabel('Nombre').fill(`Dominio ${suffix}`)
  await page.getByRole('button', { name: 'Crear' }).click()

  await expect(page.getByRole('button', { name: 'Agregar clase' })).toBeVisible()
  await expect(page.getByText('Conectado')).toBeVisible()
  return page.url()
}

/**
 * Posición de la clase en coordenadas del lienzo, no de la pantalla: React Flow la escribe en el `transform`
 * del nodo. Al recargar, `fitView` recentra la vista, así que comparar píxeles de pantalla no sirve.
 */
async function flowPosition(page: Page): Promise<{ x: number; y: number }> {
  const style = await page.locator('.react-flow__node', { hasText: 'Clase1' }).getAttribute('style')
  const match = /translate\(\s*(-?[\d.]+)px,\s*(-?[\d.]+)px\)/.exec(style ?? '')
  return match ? { x: Number(match[1]), y: Number(match[2]) } : { x: 0, y: 0 }
}

/** Agrega una clase por la paleta y espera a que el servidor la difunda. */
async function addClass(page: Page, index: number) {
  await page.getByRole('button', { name: 'Agregar clase' }).click()
  await expect(page.getByText(`Clase${index}`, { exact: true })).toBeVisible()
}

test('CP-01: no se permite una segunda relación entre las mismas clases', async ({ page }) => {
  await login(page)
  await newDiagram(page)

  await addClass(page, 1)
  await addClass(page, 2)

  // Se conectan arrastrando de un borde al otro, como en el uso normal del editor.
  const origen = page.locator('.react-flow__node', { hasText: 'Clase1' })
  const destino = page.locator('.react-flow__node', { hasText: 'Clase2' })

  for (const intento of [1, 2]) {
    await origen.locator('.react-flow__handle').first().hover()
    await page.mouse.down()
    await destino.locator('.react-flow__handle').first().hover()
    await page.mouse.up()

    await expect(page.getByRole('dialog', { name: 'Nueva relación' })).toBeVisible()
    await page.getByRole('button', { name: 'Crear relación' }).click()

    if (intento === 1) {
      await expect(page.getByRole('dialog')).toBeHidden()
    }
  }

  // La segunda se rechaza: el aviso lo envía el backend con DUPLICATE_RELATIONSHIP.
  await expect(page.getByText(/conexión entre esas clases ya existe/i)).toBeVisible()
})

test('CP-02: el cambio de posición persiste tras recargar', async ({ page }) => {
  await login(page)
  const editorUrl = await newDiagram(page)

  await addClass(page, 1)

  const clase = page.locator('.react-flow__node', { hasText: 'Clase1' })
  const antes = await flowPosition(page)
  const caja = await clase.boundingBox()
  expect(caja).not.toBeNull()

  await clase.hover()
  await page.mouse.down()
  await page.mouse.move(caja!.x + 220, caja!.y + 140, { steps: 12 })
  await page.mouse.up()

  // El movimiento viaja como MOVE_CLASS y el servidor lo persiste en el momento.
  await expect(page.getByText(/versión [2-9]/)).toBeVisible()

  await page.goto(editorUrl)
  await expect(page.getByRole('button', { name: 'Agregar clase' })).toBeVisible()

  const despues = await flowPosition(page)
  expect(Math.abs(despues.x - antes.x) + Math.abs(despues.y - antes.y)).toBeGreaterThan(50)
})

test('el panel de propiedades sobrevive a agregar y editar un atributo', async ({ page }) => {
  await login(page)
  await newDiagram(page)
  await addClass(page, 1)

  await page.locator('.react-flow__node').first().click()
  await expect(page.getByLabel('Nombre de la clase')).toBeVisible()

  const panel = page.locator('aside')
  await panel.getByRole('button', { name: 'Agregar', exact: true }).first().click()

  // La operación difundida no debe cerrar el panel ni deseleccionar la clase.
  await expect(page.getByLabel('Nombre de la clase')).toBeVisible()
  await expect(panel.getByRole('textbox', { name: /Nombre del atributo/ })).toBeVisible()

  await panel.getByRole('textbox', { name: /Tipo del atributo/ }).fill('Integer')
  await page.getByLabel('Nombre de la clase').click()

  await expect(page.locator('.react-flow__node').first()).toContainText('atributo1: Integer')
  await expect(page.getByLabel('Nombre de la clase')).toBeVisible()
})

test('desde el editor se vuelve al proyecto del diagrama', async ({ page }) => {
  await login(page)
  const suffix = Date.now().toString().slice(-6)

  await page.getByRole('button', { name: 'Nuevo proyecto' }).click()
  await page.getByLabel('Nombre').fill(`Vuelta ${suffix}`)
  await page.getByRole('button', { name: 'Crear' }).click()
  await expect(page.getByRole('dialog')).toBeHidden()
  await page.getByRole('link', { name: `Vuelta ${suffix}` }).click()
  await page.waitForURL(/\/projects\/[0-9a-f-]{36}$/)
  const projectUrl = page.url()

  await page.getByRole('button', { name: 'Nuevo diagrama' }).click()
  await page.getByLabel('Nombre').fill(`Diag ${suffix}`)
  await page.getByRole('button', { name: 'Crear' }).click()
  await expect(page.getByRole('button', { name: 'Agregar clase' })).toBeVisible()

  await page.getByRole('link', { name: 'Volver al proyecto' }).click()

  await expect(page).toHaveURL(projectUrl)
  await expect(page.getByRole('link', { name: `Diag ${suffix}` })).toBeVisible()

  // Y sigue estando tras recargar.
  await page.reload()
  await expect(page.getByRole('link', { name: `Diag ${suffix}` })).toBeVisible()
})
