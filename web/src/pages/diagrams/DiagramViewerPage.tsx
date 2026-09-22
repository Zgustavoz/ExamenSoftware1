import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, Code2, LoaderCircle, Pencil } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'
import { DiagramCanvas } from '@/components/diagram/DiagramCanvas'
import { SequenceView } from '@/components/diagram/SequenceView'
import { FormError } from '@/components/FormError'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { getDiagram } from '@/lib/api/diagrams'
import { isClassContent } from '@/lib/diagram/types'
import { hasAnyRole, useAuthStore } from '@/stores/auth-store'

/** CU-11 Consultar diagrama: solo lectura, con zoom y navegación. Sirve para clases y para secuencia. */
export default function DiagramViewerPage() {
  const { diagramId = '' } = useParams()
  const user = useAuthStore((s) => s.user)
  const isDesigner = hasAnyRole(user, ['DESIGNER'])

  const diagram = useQuery({ queryKey: ['diagram', diagramId], queryFn: () => getDiagram(diagramId) })

  if (diagram.isPending) {
    return (
      <p className="flex items-center gap-2 text-muted-foreground">
        <LoaderCircle className="size-4 animate-spin" aria-hidden />
        Cargando diagrama…
      </p>
    )
  }

  if (diagram.isError) return <FormError error={diagram.error} />

  // El backend responde `null` tanto si no existe como si es de otra empresa: no se distingue a propósito.
  if (!diagram.data) {
    return (
      <div className="rounded-xl border border-dashed py-16 text-center">
        <p className="text-muted-foreground">El diagrama no existe o no tiene acceso.</p>
        <Button asChild variant="link">
          <Link to="/projects">Volver a proyectos</Link>
        </Button>
      </div>
    )
  }

  const { name, description, type, contentJson, version, projectId } = diagram.data
  const isClass = isClassContent(contentJson)

  return (
    <>
      <Button asChild variant="ghost" size="sm" className="mb-4 -ml-2">
        <Link to={`/projects/${projectId}`}>
          <ArrowLeft className="size-4" aria-hidden />
          Volver al proyecto
        </Link>
      </Button>

      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-semibold tracking-tight">
            {name}
            <Badge variant="secondary">{type === 'SEQUENCE' ? 'Secuencia' : 'Clases'}</Badge>
            <Badge variant="outline">versión {version}</Badge>
          </h1>
          {description && <p className="mt-1 text-muted-foreground">{description}</p>}
        </div>

        <div className="flex gap-2">
          {type === 'CLASS' && (
            <Button asChild variant="outline">
              <Link to={`/diagrams/${diagramId}/code`}>
                <Code2 className="size-4" aria-hidden />
                Código
              </Link>
            </Button>
          )}
          {isDesigner && type === 'CLASS' && (
            <Button asChild variant="outline">
              <Link to={`/diagrams/${diagramId}`}>
                <Pencil className="size-4" aria-hidden />
                Editar
              </Link>
            </Button>
          )}
        </div>
      </div>

      {isClass ? (
        contentJson.classes.length === 0 ? (
          <p className="rounded-xl border border-dashed py-16 text-center text-muted-foreground">
            El diagrama está vacío.
          </p>
        ) : (
          <div className="h-[70vh] overflow-hidden rounded-xl border bg-card">
            <DiagramCanvas content={contentJson} />
          </div>
        )
      ) : (
        <SequenceView content={contentJson} />
      )}
    </>
  )
}
