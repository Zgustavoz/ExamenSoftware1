import { http } from './http'
import type { Role, User } from './types'

/** Roles que un `COMPANY_ADMIN` puede asignar: nunca `SOFTWARE_ADMIN` (CU-03). */
export const ASSIGNABLE_ROLES = ['COMPANY_ADMIN', 'DESIGNER', 'DEVELOPER'] as const
export type AssignableRole = (typeof ASSIGNABLE_ROLES)[number]

export const ROLE_LABEL: Record<Role, string> = {
  SOFTWARE_ADMIN: 'Administrador de la plataforma',
  COMPANY_ADMIN: 'Administrador de empresa',
  DESIGNER: 'Diseñador',
  DEVELOPER: 'Desarrollador',
}

export interface UserRequest {
  username: string
  email: string
  /** En la edición, vacía significa «sin cambio». */
  password: string
  fullName: string
  roles: AssignableRole[]
}

/** CU-03. El backend devuelve solo los usuarios de la empresa del token. */
export async function listUsers(): Promise<User[]> {
  const { data } = await http.get<User[]>('/users')
  return data
}

export async function createUser(body: UserRequest): Promise<User> {
  const { data } = await http.post<User>('/users', body)
  return data
}

export async function updateUser(id: string, body: UserRequest): Promise<User> {
  const { data } = await http.put<User>(`/users/${id}`, body)
  return data
}

/** Desactivar no borra: el usuario queda con `is_active = false`. */
export async function setUserActive(id: string, active: boolean): Promise<User> {
  const { data } = await http.patch<User>(`/users/${id}/status`, { active })
  return data
}
