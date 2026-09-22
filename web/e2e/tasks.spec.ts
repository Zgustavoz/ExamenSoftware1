import { expect, test, type Page } from '@playwright/test'

/**
 * CU-21 Gestionar tareas de punta a punta: crear desde la página de Tareas eligiendo diagrama y persona,
 * editarla, reasignarla y eliminarla. Requiere el stack levantado y `E2E_PASSWORD`.
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

/** Deja un proyecto con un diagrama para poder colgar la tarea de algo. */
async function seedDiagram(page: Page, suffix: string) {
  await page.getByRole('button', { name: 'Nuevo proyecto' }).click()
  await page.getByLabel('Nombre').fill(`Tareas ${suffix}`)
  await page.getByRole('button', { name: 'Crear' }).click()
  await expect(page.getByRole('dialog')).toBeHidden()

  await page.getByRole('link', { name: `Tareas ${suffix}` }).click()
  await page.waitForURL(/\/projects\/[0-9a-f-]{36}$/)
  await page.getByRole('button', { name: 'Nuevo diagrama' }).click()
  await page.getByLabel('Nombre').fill(`Diag ${suffix}`)
  await page.getByRole('button', { name: 'Crear' }).click()
  await expect(page.getByRole('button', { name: 'Agregar clase' })).toBeVisible()
}

test('CU-21: crear, editar, reasignar y eliminar una tarea', async ({ page }) => {
  const suffix = Date.now().toString().slice(-6)
  await login(page)
  await seedDiagram(page, suffix)

  await page.getByRole('link', { name: 'Tareas' }).click()
  await expect(page.getByRole('heading', { name: 'Tareas' })).toBeVisible()

  // --- Crear, eligiendo proyecto, diagrama y persona ---
  await page.getByRole('button', { name: 'Nueva tarea' }).click()
  await page.getByLabel('Título').fill(`Revisar ${suffix}`)
  await page.getByLabel('Proyecto').selectOption({ label: `Tareas ${suffix}` })
  await page.getByLabel('Diagrama').selectOption({ label: `Diag ${suffix}` })
  await page.getByLabel('Asignar a').selectOption({ label: 'Desarrollador Demo (developer)' })
  await page.getByLabel('Descripción').fill('Comprobar los nombres de las clases')
  await page.getByRole('button', { name: 'Crear tarea' }).click()
  await expect(page.getByRole('dialog')).toBeHidden()

  // Aparece en «Creadas por mí», con a quién se la asigné.
  await page.getByRole('tab', { name: 'Creadas por mí' }).click()
  const fila = page.locator('li', { hasText: `Revisar ${suffix}` }).first()
  await expect(fila).toContainText('Para Desarrollador Demo')
  await expect(fila).toContainText('Pendiente')

  // --- Editar: cambia el título y reasigna ---
  await fila.getByRole('button', { name: /Editar la tarea/ }).click()
  await page.getByLabel('Título').fill(`Revisar ${suffix} (v2)`)
  await page.getByLabel('Asignar a').selectOption({ label: 'Segundo Disenador (designer2)' })
  await page.getByRole('button', { name: 'Guardar cambios' }).click()
  await expect(page.getByRole('dialog')).toBeHidden()

  const editada = page.locator('li', { hasText: `Revisar ${suffix} (v2)` }).first()
  await expect(editada).toContainText('Para Segundo Disenador')

  // --- Eliminar, con confirmación ---
  await editada.getByRole('button', { name: 'Eliminar' }).click()
  await expect(page.getByRole('alertdialog')).toContainText(`Revisar ${suffix} (v2)`)
  await page.getByRole('button', { name: 'Eliminar la tarea' }).click()

  await expect(page.locator('li', { hasText: `Revisar ${suffix}` })).toHaveCount(0)
})

test('CU-21: quien solo tiene la tarea asignada puede avanzarla, pero no editarla', async ({ page }) => {
  const suffix = Date.now().toString().slice(-6)
  await login(page)
  await seedDiagram(page, suffix)

  await page.getByRole('link', { name: 'Tareas' }).click()
  await page.getByRole('button', { name: 'Nueva tarea' }).click()
  await page.getByLabel('Título').fill(`Para otro ${suffix}`)
  await page.getByLabel('Proyecto').selectOption({ label: `Tareas ${suffix}` })
  await page.getByLabel('Diagrama').selectOption({ label: `Diag ${suffix}` })
  await page.getByLabel('Asignar a').selectOption({ label: 'Desarrollador Demo (developer)' })
  await page.getByRole('button', { name: 'Crear tarea' }).click()
  await expect(page.getByRole('dialog')).toBeHidden()

  // Ahora entra quien la recibe.
  await page.getByRole('button', { name: 'Menú de usuario' }).click()
  await page.getByRole('menuitem', { name: 'Cerrar sesión' }).click()
  await login(page, 'developer')

  await page.getByRole('link', { name: 'Tareas' }).click()
  const fila = page.locator('li', { hasText: `Para otro ${suffix}` }).first()
  await expect(fila).toContainText('De Diseñador Demo')

  // Puede avanzar el estado, pero no gestionar la tarea: eso es de quien la encargó.
  await expect(fila.getByRole('button', { name: /Editar la tarea/ })).toHaveCount(0)
  await expect(fila.getByRole('button', { name: 'Eliminar' })).toHaveCount(0)

  await fila.getByRole('button', { name: 'Empezar' }).click()
  await expect(page.locator('li', { hasText: `Para otro ${suffix}` }).first()).toContainText('En curso')
})
