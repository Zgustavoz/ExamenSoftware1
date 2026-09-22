import type { Diagram } from './diagrams'
import { fileNameFrom } from './codegen'
import { http } from './http'

/** Límite que impone el backend al importar (sección 10.4). */
export const MAX_XMI_BYTES = 5 * 1024 * 1024
export const XMI_EXTENSIONS = ['.xmi', '.xml']

/** CU-16 Exportar: descarga el XMI 2.5.1 / UML 2.5.1 del diagrama. */
export async function exportXmi(diagramId: string, diagramName: string): Promise<void> {
  const response = await http.get<Blob>(`/diagrams/${diagramId}/xmi`, { responseType: 'blob' })
  const url = URL.createObjectURL(response.data)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = fileNameFrom(response.headers['content-disposition'], `${diagramName}.xmi`)
    document.body.append(link)
    link.click()
    link.remove()
  } finally {
    URL.revokeObjectURL(url)
  }
}

/** CU-16 Importar: crea un diagrama nuevo en el proyecto a partir del archivo. */
export async function importXmi(projectId: string, file: File): Promise<Diagram> {
  const body = new FormData()
  body.append('file', file)
  body.append('projectId', projectId)
  const { data } = await http.post<Diagram>('/diagrams/xmi/import', body, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return data
}

/** Comprueba antes de subir lo que el backend rechazaría igualmente, para avisar al instante. */
export function xmiFileProblem(file: File): string | null {
  const name = file.name.toLowerCase()
  if (!XMI_EXTENSIONS.some((extension) => name.endsWith(extension))) {
    return 'El archivo debe tener extensión .xmi o .xml.'
  }
  if (file.size > MAX_XMI_BYTES) return 'El archivo supera el máximo de 5 MB.'
  if (file.size === 0) return 'El archivo está vacío.'
  return null
}
