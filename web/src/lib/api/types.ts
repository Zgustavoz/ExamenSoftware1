export const ROLES = ['SOFTWARE_ADMIN', 'COMPANY_ADMIN', 'DESIGNER', 'DEVELOPER'] as const
export type Role = (typeof ROLES)[number]

export interface User {
  id: string
  username: string
  email: string
  fullName: string | null
  roles: Role[]
  companyId: string
  active: boolean
  createdAt: string
}

export interface LoginRequest {
  companySlug: string
  username: string
  password: string
}

export interface RegisterCompanyRequest {
  companyName: string
  companySlug: string
  admin: { fullName: string; username: string; email: string; password: string }
}

export interface LoginResponse {
  token: string
  user: User
}

/** Respuesta paginada de REST (`/api/projects`, `/api/notifications`). */
export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
