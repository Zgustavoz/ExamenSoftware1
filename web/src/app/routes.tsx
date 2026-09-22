import type { RouteObject } from 'react-router-dom'
import { HomeRedirect, RedirectIfAuthenticated, RequireAuth, RequireRole } from '@/auth/guards'
import { AppLayout } from '@/components/layout/AppLayout'
import { NotFound } from '@/pages/NotFound'

/**
 * Mapa de rutas (sección 6 y 11.1). Cada pantalla se carga de forma diferida.
 * Los roles de cada ruta siguen la tabla de autorización por CU.
 */
export const routes: RouteObject[] = [
  {
    element: <RedirectIfAuthenticated />,
    children: [
      {
        path: '/login',
        lazy: async () => ({ Component: (await import('@/pages/auth/LoginPage')).default }),
      },
      {
        path: '/register-company',
        lazy: async () => ({ Component: (await import('@/pages/auth/RegisterCompanyPage')).default }),
      },
    ],
  },
  {
    element: <RequireAuth />,
    children: [
      { index: true, element: <HomeRedirect /> },
      {
        element: <AppLayout />,
        children: [
          {
            element: <RequireRole roles={['SOFTWARE_ADMIN']} />,
            children: [
              {
                path: '/admin/companies',
                lazy: async () => ({ Component: (await import('@/pages/admin/CompaniesPage')).default }),
              },
            ],
          },
          {
            element: <RequireRole roles={['COMPANY_ADMIN']} />,
            children: [
              {
                path: '/company/users',
                lazy: async () => ({ Component: (await import('@/pages/company/UsersPage')).default }),
              },
            ],
          },
          {
            element: <RequireRole roles={['COMPANY_ADMIN', 'DESIGNER', 'DEVELOPER']} />,
            children: [
              {
                path: '/projects',
                lazy: async () => ({ Component: (await import('@/pages/projects/ProjectsPage')).default }),
              },
              {
                path: '/projects/:projectId',
                lazy: async () => ({ Component: (await import('@/pages/projects/ProjectDiagramsPage')).default }),
              },
              {
                // El editor solo tiene sentido para quien puede editar; el resto usa el visor.
                element: <RequireRole roles={['DESIGNER']} />,
                children: [
                  {
                    path: '/diagrams/:diagramId',
                    lazy: async () => ({
                      Component: (await import('@/pages/diagrams/editor/DiagramEditorPage')).default,
                    }),
                  },
                ],
              },
              {
                path: '/diagrams/:diagramId/code',
                lazy: async () => ({ Component: (await import('@/pages/diagrams/CodeGenerationPage')).default }),
              },
              {
                path: '/diagrams/:diagramId/view',
                lazy: async () => ({ Component: (await import('@/pages/diagrams/DiagramViewerPage')).default }),
              },
            ],
          },
          {
            path: '/tasks',
            lazy: async () => ({ Component: (await import('@/pages/tasks/TasksPage')).default }),
          },
          {
            path: '/notifications',
            lazy: async () => ({ Component: (await import('@/pages/notifications/NotificationsPage')).default }),
          },
        ],
      },
    ],
  },
  { path: '*', element: <NotFound /> },
]
