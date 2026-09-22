import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { LoaderCircle } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { FormError } from '@/components/FormError'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import type { Task } from '@/lib/api/codegen'
import { listDiagrams } from '@/lib/api/diagrams'
import { listProjects } from '@/lib/api/projects'
import { createTask, listAssignableUsers, updateTask } from '@/lib/api/tasks'

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Diagrama fijado: al crear desde el editor no hace falta elegirlo. */
  diagramId?: string
  /** Tarea a editar; sin ella el diálogo crea una nueva. */
  task?: Task | null
}

const SELECT_CLASS =
  'h-8 w-full rounded-lg border border-input bg-transparent px-2.5 text-sm outline-none ' +
  'focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-50'

/**
 * CU-21 Crear y editar una tarea manual. El asignado tiene que ser alguien activo de la misma empresa: si
 * no, el backend responde `USER_NOT_ELIGIBLE`. Al editar no se cambia el diagrama.
 */
export function TaskFormDialog({ open, onOpenChange, diagramId, task = null }: Props) {
  const queryClient = useQueryClient()
  const editando = task !== null

  const [title, setTitle] = useState(task?.title ?? '')
  const [description, setDescription] = useState(task?.description ?? '')
  const [assignedTo, setAssignedTo] = useState(task?.assignedTo ?? '')
  // Solo se usan cuando hay que elegir diagrama: el proyecto acota la lista.
  const [projectId, setProjectId] = useState('')
  const [elegido, setElegido] = useState('')

  const eligeDiagrama = !editando && !diagramId

  const people = useQuery({ queryKey: ['assignable-users'], queryFn: listAssignableUsers, enabled: open })
  const projects = useQuery({
    queryKey: ['projects', 'para-tareas'],
    queryFn: () => listProjects({ size: 100 }),
    enabled: open && eligeDiagrama,
  })
  const diagrams = useQuery({
    queryKey: ['diagrams', projectId],
    queryFn: () => listDiagrams(projectId),
    enabled: open && eligeDiagrama && projectId !== '',
  })

  const mutation = useMutation({
    mutationFn: () =>
      editando
        ? updateTask(task.id, { assignedTo, title: title.trim(), description: description.trim() })
        : createTask({
            diagramId: diagramId ?? elegido,
            assignedTo,
            title: title.trim(),
            description: description.trim(),
          }),
    onSuccess: async (saved) => {
      await queryClient.invalidateQueries({ queryKey: ['tasks'] })
      toast.success(editando ? `Tarea «${saved.title}» actualizada.` : `Tarea «${saved.title}» creada y asignada.`)
      onOpenChange(false)
    },
  })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    mutation.mutate()
  }

  const pending = mutation.isPending
  const listaDiagramas = diagrams.data ?? []
  const falta = !assignedTo || !title.trim() || (eligeDiagrama && !elegido)

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{editando ? 'Editar tarea' : 'Nueva tarea'}</DialogTitle>
          <DialogDescription>
            {editando
              ? 'Si cambia a quién está asignada, se avisará a quien la reciba.'
              : 'Se avisará a la persona a la que la asigne.'}
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={submit} noValidate className="grid gap-4">
          <div className="grid gap-2">
            <Label htmlFor="task-title">Título</Label>
            <Input
              id="task-title"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              maxLength={200}
              required
              autoFocus
              disabled={pending}
            />
          </div>

          {eligeDiagrama && (
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="grid gap-2">
                <Label htmlFor="task-project">Proyecto</Label>
                <select
                  id="task-project"
                  value={projectId}
                  onChange={(e) => {
                    setProjectId(e.target.value)
                    setElegido('')
                  }}
                  disabled={pending || projects.isPending}
                  className={SELECT_CLASS}
                >
                  <option value="">{projects.isPending ? 'Cargando…' : 'Elija el proyecto'}</option>
                  {projects.data?.content.map((project) => (
                    <option key={project.id} value={project.id}>
                      {project.name}
                    </option>
                  ))}
                </select>
              </div>

              <div className="grid gap-2">
                <Label htmlFor="task-diagram">Diagrama</Label>
                <select
                  id="task-diagram"
                  value={elegido}
                  onChange={(e) => setElegido(e.target.value)}
                  disabled={pending || projectId === '' || diagrams.isPending}
                  className={SELECT_CLASS}
                >
                  <option value="">
                    {projectId === ''
                      ? 'Elija antes el proyecto'
                      : diagrams.isPending
                        ? 'Cargando…'
                        : listaDiagramas.length === 0
                          ? 'El proyecto no tiene diagramas'
                          : 'Elija el diagrama'}
                  </option>
                  {listaDiagramas.map((diagram) => (
                    <option key={diagram.id} value={diagram.id}>
                      {diagram.name}
                    </option>
                  ))}
                </select>
              </div>
            </div>
          )}

          <div className="grid gap-2">
            <Label htmlFor="task-assignee">Asignar a</Label>
            {/* Un <select> nativo: la lista es corta y así no hace falta más maquinaria. */}
            <select
              id="task-assignee"
              value={assignedTo}
              onChange={(e) => setAssignedTo(e.target.value)}
              required
              disabled={pending || people.isPending}
              className={SELECT_CLASS}
            >
              <option value="">{people.isPending ? 'Cargando personas…' : 'Elija a quién asignarla'}</option>
              {people.data?.map((person) => (
                <option key={person.id} value={person.id}>
                  {person.displayName} ({person.username})
                </option>
              ))}
            </select>
          </div>

          <div className="grid gap-2">
            <Label htmlFor="task-description">Descripción</Label>
            <textarea
              id="task-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={3}
              disabled={pending}
              className="min-h-20 w-full rounded-md border bg-transparent px-3 py-2 text-sm outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:opacity-50"
            />
          </div>

          <FormError error={mutation.error ?? people.error ?? projects.error ?? diagrams.error} />

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={pending}>
              Cancelar
            </Button>
            <Button type="submit" disabled={pending || falta}>
              {pending && <LoaderCircle className="size-4 animate-spin" aria-hidden />}
              {editando ? 'Guardar cambios' : 'Crear tarea'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
