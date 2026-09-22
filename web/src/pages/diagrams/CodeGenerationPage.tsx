import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Cog, Download, FileCode, LoaderCircle } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { FormError } from '@/components/FormError'
import { PageHeader } from '@/components/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import {
  CODE_LANGUAGE,
  downloadGeneratedCode,
  generateBackendCode,
  listGenerations,
  listTasks,
} from '@/lib/api/codegen'
import { getDiagram } from '@/lib/api/diagrams'
import { errorMessage } from '@/lib/api/errors'
import { formatDate } from '@/lib/format'
import { hasAnyRole, useAuthStore } from '@/stores/auth-store'

const STATUS_LABEL: Record<string, string> = {
  PENDING: 'Pendiente',
  IN_PROGRESS: 'En curso',
  COMPLETED: 'Completada',
  FAILED: 'Fallida',
}

/** CU-14 Generar código backend y CU-15 Descargar el código generado. */
export default function CodeGenerationPage() {
  const { diagramId = '' } = useParams()
  const queryClient = useQueryClient()
  const user = useAuthStore((s) => s.user)
  const canGenerate = hasAnyRole(user, ['DESIGNER', 'DEVELOPER'])
  const canDownload = hasAnyRole(user, ['DEVELOPER'])

  const diagram = useQuery({ queryKey: ['diagram', diagramId], queryFn: () => getDiagram(diagramId) })
  const tasks = useQuery({ queryKey: ['tasks', diagramId], queryFn: () => listTasks(diagramId) })
  const generations = useQuery({
    queryKey: ['generations', diagramId],
    queryFn: () => listGenerations(diagramId),
    // El historial con los archivos solo lo sirve el backend al DEVELOPER (CU-15).
    enabled: canDownload,
  })

  const generate = useMutation({
    mutationFn: () => generateBackendCode(diagramId),
    onSuccess: async (task) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['tasks', diagramId] }),
        queryClient.invalidateQueries({ queryKey: ['generations', diagramId] }),
      ])
      toast.success(
        task.status === 'COMPLETED' ? 'El código se generó correctamente.' : `Tarea ${STATUS_LABEL[task.status]}.`,
      )
    },
  })

  const download = useMutation({
    mutationFn: downloadGeneratedCode,
    onError: (error) => toast.error(errorMessage(error)),
  })

  const codeTasks = (tasks.data ?? []).filter((task) => task.type === 'CODE_GENERATION')

  return (
    <>
      <Button asChild variant="ghost" size="sm" className="mb-4 -ml-2">
        <Link to={diagram.data ? `/projects/${diagram.data.projectId}` : '/projects'}>
          <ArrowLeft className="size-4" aria-hidden />
          Volver al proyecto
        </Link>
      </Button>

      <PageHeader
        title="Código backend"
        description={`Java / Spring Boot a partir de «${diagram.data?.name ?? 'el diagrama'}».`}
      >
        {canGenerate && (
          <Button onClick={() => generate.mutate()} disabled={generate.isPending}>
            {generate.isPending ? (
              <LoaderCircle className="size-4 animate-spin" aria-hidden />
            ) : (
              <Cog className="size-4" aria-hidden />
            )}
            Generar código
          </Button>
        )}
      </PageHeader>

      {/* Un diagrama incompleto se rechaza aquí y no llega a crear ninguna tarea (CP-06). */}
      <FormError error={generate.error} />

      <section className="mt-6 grid gap-6 lg:grid-cols-2">
        <div>
          <h2 className="mb-3 font-medium">Generaciones</h2>

          {tasks.isPending && (
            <p className="flex items-center gap-2 text-muted-foreground">
              <LoaderCircle className="size-4 animate-spin" aria-hidden />
              Cargando…
            </p>
          )}

          {tasks.isError && <FormError error={tasks.error} />}

          {tasks.isSuccess && codeTasks.length === 0 && (
            <p className="rounded-xl border border-dashed py-10 text-center text-muted-foreground">
              Todavía no se generó código para este diagrama.
            </p>
          )}

          <ul className="grid gap-3">
            {codeTasks.map((task) => (
              <li key={task.id}>
                <Card>
                  <CardHeader>
                    <CardTitle className="flex items-center justify-between gap-2 text-sm">
                      {task.title}
                      <Badge variant={task.status === 'COMPLETED' ? 'default' : 'secondary'}>
                        {STATUS_LABEL[task.status] ?? task.status}
                      </Badge>
                    </CardTitle>
                  </CardHeader>
                  <CardContent className="flex items-center justify-between gap-3">
                    <p className="text-xs text-muted-foreground">
                      {CODE_LANGUAGE} · {formatDate(task.createdAt)}
                    </p>
                    {canDownload && task.status === 'COMPLETED' && (
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => download.mutate(task.id)}
                        disabled={download.isPending}
                      >
                        <Download className="size-4" aria-hidden />
                        Descargar ZIP
                      </Button>
                    )}
                  </CardContent>
                </Card>
              </li>
            ))}
          </ul>
        </div>

        {canDownload && (
          <div>
            <h2 className="mb-3 font-medium">Archivos generados</h2>

            {generations.isError && <FormError error={generations.error} />}

            {generations.isSuccess && generations.data.length === 0 && (
              <p className="rounded-xl border border-dashed py-10 text-center text-muted-foreground">
                Sin archivos todavía.
              </p>
            )}

            <ul className="grid gap-3">
              {generations.data?.map((generation) => (
                <li key={generation.taskId} className="rounded-xl border p-3">
                  <p className="mb-2 text-xs text-muted-foreground">
                    {formatDate(generation.createdAt)} · {generation.files.length} archivos
                  </p>
                  <ul className="grid gap-1">
                    {generation.files.map((file) => (
                      <li key={file.id} className="flex items-center gap-2 font-mono text-xs">
                        <FileCode className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
                        <span className="truncate">{file.fileName}</span>
                      </li>
                    ))}
                  </ul>
                </li>
              ))}
            </ul>
          </div>
        )}
      </section>
    </>
  )
}
