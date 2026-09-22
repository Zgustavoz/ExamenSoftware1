import type { Role, User } from '@/lib/api/types'

/** Redirección post-login por rol (sección 6). Si el usuario tiene varios roles gana el de más alcance. */
const HOME_BY_ROLE: Record<Role, string> = {
  SOFTWARE_ADMIN: '/admin/companies',
  COMPANY_ADMIN: '/company/users',
  DESIGNER: '/projects',
  DEVELOPER: '/projects',
}

const PRIORITY: Role[] = ['SOFTWARE_ADMIN', 'COMPANY_ADMIN', 'DESIGNER', 'DEVELOPER']

export function homeFor(user: User): string {
  const role = PRIORITY.find((r) => user.roles.includes(r))
  return role ? HOME_BY_ROLE[role] : '/login'
}
