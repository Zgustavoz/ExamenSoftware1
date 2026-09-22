import { gql } from './graphql'
import { http } from './http'

export interface Task {
  id: string
  diagramId: string | null
  type: string
  title: string
  description: string | null
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED'
  resultJson: Record<string, unknown> | null
  assignedTo: string | null
  /** Nombre de la persona asignada; el backend lo resuelve solo si se pide (CU-21). */
  assignedToName?: string | null
  createdBy: string | null
  createdByName?: string | null
  createdAt: string
  startedAt: string | null
  completedAt: string | null
}

export interface GeneratedFile {
  id: string
  fileName: string
  status: 'SUCCESS' | 'FAILED'
}

export interface Generation {
  taskId: string
  diagramId: string
  language: string
  status: string
  createdAt: string
  files: GeneratedFile[]
}

const TASK_FIELDS = `
  id diagramId type title description status resultJson assignedTo createdBy createdAt startedAt completedAt
`

/** El único destino soportado es Java/Spring Boot (D-20). */
export const CODE_LANGUAGE = 'JAVA'

/**
 * CU-14. El backend valida el diagrama antes de crear la tarea: si está incompleto responde
 * `DIAGRAM_INCOMPLETE` y **no** queda ninguna tarea registrada (CP-06).
 */
export async function generateBackendCode(diagramId: string, language = CODE_LANGUAGE): Promise<Task> {
  const data = await gql<{ generateBackendCode: Task }>(
    `mutation GenerateBackendCode($diagramId: ID!, $language: String!) {
       generateBackendCode(diagramId: $diagramId, language: $language) { ${TASK_FIELDS} }
     }`,
    { diagramId, language },
  )
  return data.generateBackendCode
}

export async function listTasks(diagramId: string): Promise<Task[]> {
  const data = await gql<{ tasks: Task[] }>(
    `query Tasks($diagramId: ID) { tasks(diagramId: $diagramId) { ${TASK_FIELDS} } }`,
    { diagramId },
  )
  return data.tasks
}

/** CU-15. Historial de generaciones agrupado por tarea (solo `DEVELOPER`). */
export async function listGenerations(diagramId: string): Promise<Generation[]> {
  const { data } = await http.get<Generation[]>('/generated-code', { params: { diagramId } })
  return data
}

/** CU-15. Descarga el ZIP de una tarea y dispara la descarga en el navegador. */
export async function downloadGeneratedCode(taskId: string): Promise<void> {
  const response = await http.get<Blob>(`/generated-code/tasks/${taskId}/download`, { responseType: 'blob' })
  const url = URL.createObjectURL(response.data)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = fileNameFrom(response.headers['content-disposition'], `codigo-${taskId}.zip`)
    document.body.append(link)
    link.click()
    link.remove()
  } finally {
    URL.revokeObjectURL(url)
  }
}

/** Toma el nombre que propone el servidor en `Content-Disposition`. */
export function fileNameFrom(header: unknown, fallback: string): string {
  if (typeof header !== 'string') return fallback
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header)
  return match ? decodeURIComponent(match[1]) : fallback
}
