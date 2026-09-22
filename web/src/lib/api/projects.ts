import { http } from './http'
import type { Page } from './types'

export interface Project {
  id: string
  name: string
  description: string | null
  ownerId: string | null
  ownerName: string | null
  createdAt: string
  updatedAt: string
}

export interface ProjectRequest {
  name: string
  description: string
}

/** CU-05. El backend devuelve solo los proyectos de la empresa del token. */
export async function listProjects(params: { q?: string; page?: number; size?: number } = {}): Promise<Page<Project>> {
  const { data } = await http.get<Page<Project>>('/projects', {
    params: { q: params.q || undefined, page: params.page ?? 0, size: params.size ?? 12 },
  })
  return data
}

export async function getProject(id: string): Promise<Project> {
  const { data } = await http.get<Project>(`/projects/${id}`)
  return data
}

/** CU-04. El `owner_id` lo pone el backend con el usuario del token. */
export async function createProject(body: ProjectRequest): Promise<Project> {
  const { data } = await http.post<Project>('/projects', body)
  return data
}

/** CU-23. Solo el administrador de la empresa o el propietario del proyecto. */
export async function updateProject(id: string, body: ProjectRequest): Promise<Project> {
  const { data } = await http.put<Project>(`/projects/${id}`, body)
  return data
}

/** CU-23. Arrastra los diagramas del proyecto y, con ellos, sus tareas, código y conversaciones. */
export async function deleteProject(id: string): Promise<void> {
  await http.delete(`/projects/${id}`)
}
