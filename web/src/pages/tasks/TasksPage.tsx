import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CircleCheck, CirclePlay, LoaderCircle, Pencil, Plus, Trash2, User } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { ConfirmButton } from '@/components/ConfirmButton'
import { FormError } from '@/components/FormError'
import { PageHeader } from '@/components/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import type { Task } from '@/lib/api/codegen'
import { errorMessage } from '@/lib/api/errors'
import {
  deleteTask,
  listMyTasks,
  listTasksCreatedByMe,
  NEXT_STATUS,
  STATUS_LABEL,
  TASK_TYPE_LABEL,
  updateTaskStatus,
  type TaskStatus,
} from '@/lib/api/tasks'
import { formatDate } from '@/lib/format'
import { cn } from '@/lib/utils'
import { useAuthStore } from '@/stores/auth-store'
import { TaskFormDialog } from './TaskFormDialog'

type Pestana = 'mine' | 'created'

const FILTROS: { value: TaskStatus | ''; label: string }[] = [
  { value: '', label: 'Todas' },
  { value: 'PENDING', label: 'Pendientes' },
  { value: 'IN_PROGRESS', label: 'En curso' },
  { value: 'COMPLETED', label: 'Completadas' },
]

const COLOR: Record<TaskStatus, string> = {
  PENDING: 'border-amber-300 bg-amber-50 text-amber-800',
  IN_PROGRESS: 'border-sky-300 bg-sky-50 text-sky-800',
  COMPLETED: 'border-emerald-300 bg-emerald-50 text-emerald-800',
  FAILED: 'border-destructive/40 bg-destructive/5 text-destructive',
}

/** CU-21 Gestionar tareas: lo que me toca y lo que asigné a otras personas. */
export default function TasksPage() {
  const queryClient = useQueryClient()
  const currentUserId = useAuthStore((s) => s.user?.id)
  const [pestana, setPestana] = useState<Pestana>('mine')
  const [status, setStatus] = useState<TaskStatus | ''>('')
  const [creando, setCreando] = useState(false)
  const [editando, setEditando] = useState<Task | null>(null)

  const mine = useQuery({
    queryKey: ['tasks', 'mine', { status }],
    queryFn: () => listMyTasks(status || undefined),
    placeholderData: keepPreviousData,
    enabled: pestana === 'mine',
  })
  const created = useQuery({
    queryKey: ['tasks', 'created'],
    queryFn: listTasksCreatedByMe,
    enabled: pestana === 'created',
  })

  const refrescar = () => queryClient.invalidateQueries({ queryKey: ['tasks'] })

  const avanzar = useMutation({
    mutationFn: ({ id, next }: { id: string; next: TaskStatus }) => updateTaskStatus(id, next),
    onSuccess: async (task) => {
      await refrescar()
      toast.success(`«${task.title}» pasó a ${STATUS_LABEL[task.status].toLowerCase()}.`)
    },
    onError: (error) => toast.error(errorMessage(error)),
  })

  const eliminar = useMutation({
    mutationFn: deleteTask,
    onSuccess: async () => {
      await refrescar()
      toast.success('Tarea eliminada.')
    },
    onError: (error) => toast.error(errorMessage(error)),
  })

  const activa = pestana === 'mine' ? mine : created
  const tasks: Task[] = activa.data ?? []

  return (
    <>
      <PageHeader title="Tareas" description="Lo que tiene pendiente y lo que encargó a otras personas.">
        <Button onClick={() => setCreando(true)}>
          <Plus className="size-4" aria-hidden />
          Nueva tarea
        </Button>
      </PageHeader>

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <div className="flex gap-1 rounded-lg bg-muted p-1" role="tablist" aria-label="Tipo de tareas">
          {(
            [
              { value: 'mine', label: 'Mis tareas' },
              { value: 'created', label: 'Creadas por mí' },
            ] as const
          ).map((tab) => (
            <button
              key={tab.value}
              role="tab"
              aria-selected={pestana === tab.value}
              onClick={() => setPestana(tab.value)}
              className={cn(
                'rounded-md px-3 py-1 text-sm transition-colors',
                pestana === tab.value ? 'bg-background font-medium shadow-sm' : 'text-muted-foreground',
              )}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {pestana === 'mine' && (
          <div className="flex flex-wrap gap-1">
            {FILTROS.map((f) => (
              <Button
                key={f.value}
                size="sm"
                variant={status === f.value ? 'default' : 'outline'}
                onClick={() => setStatus(f.value)}
              >
                {f.label}
              </Button>
            ))}
          </div>
        )}
      </div>

      {activa.isPending && (
        <p className="flex items-center gap-2 text-muted-foreground">
          <LoaderCircle className="size-4 animate-spin" aria-hidden />
          Cargando tareas…
        </p>
      )}

      {activa.isError && <FormError error={activa.error} />}

      {activa.data &&
        (tasks.length === 0 ? (
          <p className="rounded-xl border border-dashed py-16 text-center text-muted-foreground">
            {pestana === 'mine'
              ? 'No tiene tareas asignadas.'
              : 'Todavía no ha encargado ninguna tarea.'}
          </p>
        ) : (
          <ul className="grid gap-3">
            {tasks.map((task) => {
              const next = NEXT_STATUS[task.status]
              const esManual = task.type === 'MANUAL'
              const puedeAvanzar = next !== undefined && esManual
              // Editar y eliminar son cosa de quien la encargó; el backend lo vuelve a comprobar.
              const puedeGestionar = esManual && task.createdBy === currentUserId

              return (
                <li key={task.id} className="flex flex-wrap items-start justify-between gap-4 rounded-xl border p-4">
                  <div className="min-w-0">
                    <p className="flex flex-wrap items-center gap-2 font-medium">
                      {task.title}
                      <Badge variant="outline" className={COLOR[task.status]}>
                        {STATUS_LABEL[task.status]}
                      </Badge>
                      {!esManual && <Badge variant="secondary">{TASK_TYPE_LABEL[task.type] ?? task.type}</Badge>}
                    </p>

                    {task.description && <p className="mt-1 text-sm text-muted-foreground">{task.description}</p>}

                    <p className="mt-1 flex flex-wrap items-center gap-x-1.5 gap-y-1 text-xs text-muted-foreground">
                      {esManual && (
                        <span className="flex items-center gap-1">
                          <User className="size-3.5" aria-hidden />
                          {/* En «Mis tareas» interesa quién la encargó; en «Creadas por mí», a quién se la di. */}
                          {pestana === 'mine'
                            ? `De ${task.createdByName ?? 'alguien de la empresa'}`
                            : `Para ${task.assignedToName ?? 'sin asignar'}`}
                        </span>
                      )}
                      <span>· {formatDate(task.createdAt)}</span>
                      {task.diagramId && (
                        <>
                          <span>·</span>
                          <Link to={`/diagrams/${task.diagramId}/view`} className="hover:underline">
                            Ver el diagrama
                          </Link>
                        </>
                      )}
                    </p>
                  </div>

                  <div className="flex flex-wrap items-center gap-2">
                    {puedeAvanzar && (
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => avanzar.mutate({ id: task.id, next })}
                        disabled={avanzar.isPending}
                      >
                        {next === 'IN_PROGRESS' ? (
                          <CirclePlay className="size-4" aria-hidden />
                        ) : (
                          <CircleCheck className="size-4" aria-hidden />
                        )}
                        {next === 'IN_PROGRESS' ? 'Empezar' : 'Completar'}
                      </Button>
                    )}

                    {puedeGestionar && (
                      <>
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => setEditando(task)}
                          aria-label={`Editar la tarea ${task.title}`}
                        >
                          <Pencil className="size-4" aria-hidden />
                          Editar
                        </Button>

                        <ConfirmButton
                          title={`¿Eliminar «${task.title}»?`}
                          description="La tarea desaparecerá para quien la tiene asignada. No se puede deshacer."
                          confirmLabel="Eliminar la tarea"
                          disabled={eliminar.isPending}
                          onConfirm={() => eliminar.mutate(task.id)}
                        >
                          <Trash2 className="size-4" aria-hidden />
                          Eliminar
                        </ConfirmButton>
                      </>
                    )}
                  </div>
                </li>
              )
            })}
          </ul>
        ))}

      {creando && <TaskFormDialog open onOpenChange={(open) => !open && setCreando(false)} />}

      {editando && (
        <TaskFormDialog
          key={editando.id}
          open
          task={editando}
          onOpenChange={(open) => !open && setEditando(null)}
        />
      )}
    </>
  )
}
