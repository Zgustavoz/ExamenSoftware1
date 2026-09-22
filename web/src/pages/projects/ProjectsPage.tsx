import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight, LoaderCircle, Plus, Search, User } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { FormError } from '@/components/FormError'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { listProjects } from '@/lib/api/projects'
import { formatDate } from '@/lib/format'
import { useDebounced } from '@/lib/use-debounced'
import { hasAnyRole, useAuthStore } from '@/stores/auth-store'
import { ProjectActions } from './ProjectActions'
import { ProjectFormDialog } from './ProjectFormDialog'

/** CU-05 Consultar proyectos y CU-04 Crear proyecto. */
export default function ProjectsPage() {
  const user = useAuthStore((s) => s.user)
  const canCreate = hasAnyRole(user, ['COMPANY_ADMIN', 'DESIGNER'])

  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [creating, setCreating] = useState(false)
  const query = useDebounced(search)

  const projects = useQuery({
    queryKey: ['projects', { q: query, page }],
    queryFn: () => listProjects({ q: query, page }),
    placeholderData: keepPreviousData,
  })

  const data = projects.data

  return (
    <>
      <PageHeader title="Proyectos" description="Los proyectos de su empresa.">
        {canCreate && (
          <Button onClick={() => setCreating(true)}>
            <Plus className="size-4" aria-hidden />
            Nuevo proyecto
          </Button>
        )}
      </PageHeader>

      <div className="relative mb-6 max-w-sm">
        <Search className="absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden />
        <Input
          type="search"
          value={search}
          // Al tocar el filtro se vuelve a la primera página: si no, se podría quedar en una que ya no existe.
          onChange={(e) => {
            setSearch(e.target.value)
            setPage(0)
          }}
          placeholder="Buscar por nombre o descripción"
          aria-label="Buscar proyectos"
          className="pl-9"
        />
      </div>

      {projects.isPending && (
        <p className="flex items-center gap-2 text-muted-foreground">
          <LoaderCircle className="size-4 animate-spin" aria-hidden />
          Cargando proyectos…
        </p>
      )}

      {projects.isError && <FormError error={projects.error} />}

      {data &&
        (data.content.length === 0 ? (
          <p className="rounded-xl border border-dashed py-16 text-center text-muted-foreground">
            {query ? `Ningún proyecto coincide con «${query}».` : 'Todavía no hay proyectos.'}
          </p>
        ) : (
          <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {data.content.map((project) => (
              <li key={project.id}>
                <Card className="h-full transition-shadow hover:shadow-md">
                  <CardHeader className="flex-row items-start justify-between gap-2">
                    <CardTitle>
                      <Link to={`/projects/${project.id}`} className="hover:underline">
                        {project.name}
                      </Link>
                    </CardTitle>
                    <ProjectActions project={project} />
                  </CardHeader>
                  <CardContent className="grid gap-3">
                    <p className="line-clamp-3 text-muted-foreground">{project.description || 'Sin descripción.'}</p>
                    <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
                      <User className="size-3.5" aria-hidden />
                      {project.ownerName ?? 'Sin responsable'} · {formatDate(project.createdAt)}
                    </p>
                  </CardContent>
                </Card>
              </li>
            ))}
          </ul>
        ))}

      {data && data.totalPages > 1 && (
        <nav aria-label="Paginación" className="mt-6 flex items-center justify-center gap-4">
          <Button variant="outline" size="sm" onClick={() => setPage((p) => p - 1)} disabled={data.page === 0}>
            <ChevronLeft className="size-4" aria-hidden />
            Anterior
          </Button>
          <span className="text-sm text-muted-foreground">
            Página {data.page + 1} de {data.totalPages}
          </span>
          <Button
            variant="outline"
            size="sm"
            onClick={() => setPage((p) => p + 1)}
            disabled={data.page >= data.totalPages - 1}
          >
            Siguiente
            <ChevronRight className="size-4" aria-hidden />
          </Button>
        </nav>
      )}

      {creating && <ProjectFormDialog open onOpenChange={(open) => !open && setCreating(false)} />}
    </>
  )
}
