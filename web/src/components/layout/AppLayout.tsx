import { Bell, Building2, FolderKanban, ListChecks, LogOut, Users } from 'lucide-react'
import { useEffect } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import type { Role } from '@/lib/api/types'
import { disablePush, listenForeground, refreshPushToken } from '@/lib/push/push'
import { queryClient } from '@/lib/query-client'
import { cn } from '@/lib/utils'
import { useOfflineSync } from '@/lib/offline/use-offline-queue'
import { useAuthStore } from '@/stores/auth-store'

interface NavItem {
  to: string
  label: string
  icon: typeof Building2
  roles: readonly Role[]
}

/** Cada enlace se muestra solo a los roles que pueden usar esa pantalla (tabla de autorización por CU). */
const NAV: NavItem[] = [
  { to: '/admin/companies', label: 'Empresas', icon: Building2, roles: ['SOFTWARE_ADMIN'] },
  { to: '/company/users', label: 'Usuarios', icon: Users, roles: ['COMPANY_ADMIN'] },
  {
    to: '/projects',
    label: 'Proyectos',
    icon: FolderKanban,
    roles: ['COMPANY_ADMIN', 'DESIGNER', 'DEVELOPER'],
  },
  {
    to: '/tasks',
    label: 'Tareas',
    icon: ListChecks,
    roles: ['COMPANY_ADMIN', 'DESIGNER', 'DEVELOPER'],
  },
  {
    to: '/notifications',
    label: 'Notificaciones',
    icon: Bell,
    roles: ['SOFTWARE_ADMIN', 'COMPANY_ADMIN', 'DESIGNER', 'DEVELOPER'],
  },
]

const ROLE_LABEL: Record<Role, string> = {
  SOFTWARE_ADMIN: 'Administrador de la plataforma',
  COMPANY_ADMIN: 'Administrador de empresa',
  DESIGNER: 'Diseñador',
  DEVELOPER: 'Desarrollador',
}

function initials(name: string): string {
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('')
}

/** Marco común de las pantallas con sesión iniciada: cabecera, navegación por rol y menú de usuario. */
export function AppLayout() {
  const user = useAuthStore((s) => s.user)
  const logout = useAuthStore((s) => s.logout)
  const navigate = useNavigate()
  const userId = user?.id

  // Modo offline del Copilot: envía solas las instrucciones guardadas cuando vuelve la conexión.
  useOfflineSync()

  // Push web (CU-19): renueva el token si el usuario ya dio permiso y muestra los avisos que llegan con la
  // pestaña en primer plano. Sin configuración de Firebase no hace nada.
  useEffect(() => {
    if (!userId) return
    void refreshPushToken()
    let stop = () => {}
    let cancelled = false
    void listenForeground((notification) => {
      toast(notification.title, { description: notification.body })
      void queryClient.invalidateQueries({ queryKey: ['notifications'] })
    }).then((unsubscribe) => {
      if (cancelled) unsubscribe()
      else stop = unsubscribe
    })
    return () => {
      cancelled = true
      stop()
    }
  }, [userId])

  if (!user) return null

  const items = NAV.filter((item) => user.roles.some((role) => item.roles.includes(role)))
  const displayName = user.fullName ?? user.username

  const signOut = () => {
    void disablePush() // que la cuenta anterior no siga recibiendo avisos en este navegador
    logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="min-h-svh bg-background">
      <header className="sticky top-0 z-10 border-b bg-card/80 backdrop-blur">
        <div className="mx-auto flex h-14 max-w-6xl items-center gap-6 px-4">
          <span className="flex items-center gap-2 font-medium">
            <Building2 className="size-5 text-primary" aria-hidden />
            <span className="hidden sm:inline">Diagramas UML</span>
          </span>

          <nav aria-label="Principal" className="flex flex-1 items-center gap-1">
            {items.map(({ to, label, icon: Icon }) => (
              <NavLink
                key={to}
                to={to}
                className={({ isActive }) =>
                  cn(
                    'flex items-center gap-2 rounded-md px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground',
                    isActive && 'bg-accent font-medium text-accent-foreground',
                  )
                }
              >
                <Icon className="size-4" aria-hidden />
                <span className="hidden sm:inline">{label}</span>
              </NavLink>
            ))}
          </nav>

          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" className="gap-2 px-2" aria-label="Menú de usuario">
                <span className="flex size-7 items-center justify-center rounded-full bg-primary/10 text-xs font-medium text-primary">
                  {initials(displayName)}
                </span>
                <span className="hidden text-sm sm:inline">{displayName}</span>
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-56">
              <DropdownMenuLabel className="font-normal">
                <span className="block font-medium">{displayName}</span>
                <span className="block text-xs text-muted-foreground">{user.email}</span>
                <span className="mt-1 block text-xs text-muted-foreground">
                  {user.roles.map((role) => ROLE_LABEL[role]).join(' · ')}
                </span>
              </DropdownMenuLabel>
              <DropdownMenuSeparator />
              <DropdownMenuItem onSelect={signOut}>
                <LogOut className="size-4" aria-hidden />
                Cerrar sesión
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </header>

      <main className="mx-auto max-w-6xl px-4 py-8">
        <Outlet />
      </main>
    </div>
  )
}
