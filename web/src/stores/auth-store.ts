import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { Role, User } from '@/lib/api/types'

interface AuthState {
  token: string | null
  user: User | null
  login: (token: string, user: User) => void
  logout: () => void
}

/**
 * Sesión del usuario. Se guarda en `localStorage` para sobrevivir a una recarga. El `companyId` solo sirve para
 * mostrar datos: el backend toma el `company_id` siempre del JWT.
 */
export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      user: null,
      login: (token, user) => set({ token, user }),
      logout: () => set({ token: null, user: null }),
    }),
    {
      name: 'diagramas.session',
      partialize: (state) => ({ token: state.token, user: state.user }),
    },
  ),
)

export function hasAnyRole(user: User | null, roles: readonly Role[]): boolean {
  return user !== null && user.roles.some((role) => roles.includes(role))
}
