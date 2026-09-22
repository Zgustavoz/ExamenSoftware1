import { Navigate, Outlet, useLocation } from 'react-router-dom'
import type { Role } from '@/lib/api/types'
import { homeFor } from '@/lib/roles'
import { hasAnyRole, useAuthStore } from '@/stores/auth-store'

/** Exige sesión iniciada; si no la hay, lleva al login y recuerda a dónde iba. */
export function RequireAuth() {
  const token = useAuthStore((s) => s.token)
  const location = useLocation()
  if (!token) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  return <Outlet />
}

/**
 * Exige alguno de los roles indicados. El backend es la autoridad (responde `FORBIDDEN`); esto solo evita
 * mostrar pantallas que el usuario no puede usar y lo devuelve a la que le corresponde.
 */
export function RequireRole({ roles }: { roles: readonly Role[] }) {
  const user = useAuthStore((s) => s.user)
  if (!user) return <Navigate to="/login" replace />
  if (!hasAnyRole(user, roles)) return <Navigate to={homeFor(user)} replace />
  return <Outlet />
}

/** Redirige fuera del login (o de `/`) si ya hay sesión. */
export function RedirectIfAuthenticated() {
  const user = useAuthStore((s) => s.user)
  const token = useAuthStore((s) => s.token)
  if (token && user) return <Navigate to={homeFor(user)} replace />
  return <Outlet />
}

/** Ruta `/`: envía a cada usuario a la pantalla de su rol. */
export function HomeRedirect() {
  const user = useAuthStore((s) => s.user)
  return <Navigate to={user ? homeFor(user) : '/login'} replace />
}
