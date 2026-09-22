import { useMutation, useQueryClient } from '@tanstack/react-query'
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
import { createProject, updateProject, type Project } from '@/lib/api/projects'

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Si viene un proyecto se edita (CU-23); si no, se crea uno nuevo (CU-04). */
  project?: Project
}

/**
 * CU-04 Crear proyecto y CU-23 Editar proyecto. El nombre no puede repetirse dentro de la empresa
 * (`DUPLICATE_PROJECT`), salvo el del propio proyecto que se está editando.
 */
export function ProjectFormDialog({ open, onOpenChange, project }: Props) {
  const queryClient = useQueryClient()
  const editing = project !== undefined
  const [name, setName] = useState(project?.name ?? '')
  const [description, setDescription] = useState(project?.description ?? '')

  const mutation = useMutation({
    mutationFn: (body: { name: string; description: string }) =>
      editing ? updateProject(project.id, body) : createProject(body),
    onSuccess: async (saved) => {
      await queryClient.invalidateQueries({ queryKey: ['projects'] })
      await queryClient.invalidateQueries({ queryKey: ['project', saved.id] })
      toast.success(editing ? `Proyecto «${saved.name}» actualizado.` : `Proyecto «${saved.name}» creado.`)
      onOpenChange(false)
    },
  })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    mutation.mutate({ name: name.trim(), description: description.trim() })
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{editing ? 'Editar proyecto' : 'Nuevo proyecto'}</DialogTitle>
          <DialogDescription>Agrupa los diagramas de un mismo trabajo.</DialogDescription>
        </DialogHeader>

        <form onSubmit={submit} noValidate className="grid gap-4">
          <div className="grid gap-2">
            <Label htmlFor="project-name">Nombre</Label>
            <Input
              id="project-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              maxLength={100}
              required
              autoFocus
              disabled={mutation.isPending}
            />
          </div>

          <div className="grid gap-2">
            <Label htmlFor="project-description">Descripción</Label>
            <textarea
              id="project-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={3}
              disabled={mutation.isPending}
              className="min-h-20 w-full rounded-md border bg-transparent px-3 py-2 text-sm outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:opacity-50"
            />
          </div>

          <FormError error={mutation.error} />

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={mutation.isPending}>
              Cancelar
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending && <LoaderCircle className="size-4 animate-spin" aria-hidden />}
              {editing ? 'Guardar' : 'Crear'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
