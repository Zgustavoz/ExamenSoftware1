import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, GitBranch, LoaderCircle, Plus, Shapes, Upload } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { FormError } from '@/components/FormError'
import { PageHeader } from '@/components/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { listDiagrams } from '@/lib/api/diagrams'
import { getProject } from '@/lib/api/projects'
import { formatDate } from '@/lib/format'
import { hasAnyRole, useAuthStore } from '@/stores/auth-store'
import { DiagramFormDialog } from './DiagramFormDialog'
import { XmiImportDialog } from './XmiImportDialog'

/** Diagramas de un proyecto: punto de entrada a CU-06 (crear) y CU-11 (consultar). */
export default function ProjectDiagramsPage() {
  const { projectId = '' } = useParams()
  const user = useAuthStore((s) => s.user)
  const isDesigner = hasAnyRole(user, ['DESIGNER'])
  const [creating, setCreating] = useState(false)
  const [importing, setImporting] = useState(false)

  const project = useQuery({ queryKey: ['project', projectId], queryFn: () => getProject(projectId) })
  const diagrams = useQuery({ queryKey: ['diagrams', projectId], queryFn: () => listDiagrams(projectId) })

  return (
    <>
      <Button asChild variant="ghost" size="sm" className="mb-4 -ml-2">
        <Link to="/projects">
          <ArrowLeft className="size-4" aria-hidden />
          Proyectos
        </Link>
      </Button>

      <PageHeader
        title={project.data?.name ?? 'Proyecto'}
        description={project.data?.description || 'Diagramas de este proyecto.'}
      >
        {isDesigner && (
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => setImporting(true)}>
              <Upload className="size-4" aria-hidden />
              Importar XMI
            </Button>
            <Button onClick={() => setCreating(true)}>
              <Plus className="size-4" aria-hidden />
              Nuevo diagrama
            </Button>
          </div>
        )}
      </PageHeader>

      {(project.isError || diagrams.isError) && <FormError error={project.error ?? diagrams.error} />}

      {diagrams.isPending && (
        <p className="flex items-center gap-2 text-muted-foreground">
          <LoaderCircle className="size-4 animate-spin" aria-hidden />
          Cargando diagramas…
        </p>
      )}

      {diagrams.isSuccess &&
        (diagrams.data.length === 0 ? (
          <p className="rounded-xl border border-dashed py-16 text-center text-muted-foreground">
            Todavía no hay diagramas en este proyecto.
          </p>
        ) : (
          <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {diagrams.data.map((diagram) => {
              const isSequence = diagram.type === 'SEQUENCE'
              // Los diagramas de secuencia son derivados: siempre de solo lectura.
              const to = isDesigner && !isSequence ? `/diagrams/${diagram.id}` : `/diagrams/${diagram.id}/view`
              return (
                <li key={diagram.id}>
                  <Card className="h-full transition-shadow hover:shadow-md">
                    <CardHeader>
                      <CardTitle className="flex items-center gap-2">
                        {isSequence ? (
                          <GitBranch className="size-4 text-primary" aria-hidden />
                        ) : (
                          <Shapes className="size-4 text-primary" aria-hidden />
                        )}
                        <Link to={to} className="hover:underline">
                          {diagram.name}
                        </Link>
                      </CardTitle>
                    </CardHeader>
                    <CardContent className="grid gap-3">
                      <p className="line-clamp-2 text-muted-foreground">
                        {diagram.description || 'Sin descripción.'}
                      </p>
                      <p className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                        <Badge variant="secondary">{isSequence ? 'Secuencia' : 'Clases'}</Badge>
                        <span>versión {diagram.version}</span>
                        <span>· {formatDate(diagram.updatedAt)}</span>
                      </p>
                    </CardContent>
                  </Card>
                </li>
              )
            })}
          </ul>
        ))}

      {creating && (
        <DiagramFormDialog projectId={projectId} open onOpenChange={(open) => !open && setCreating(false)} />
      )}

      {importing && (
        <XmiImportDialog projectId={projectId} open onOpenChange={(open) => !open && setImporting(false)} />
      )}
    </>
  )
}
