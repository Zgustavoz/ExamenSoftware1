import { useMutation, useQueryClient } from '@tanstack/react-query'
import { MoreVertical, Pencil, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { errorMessage } from '@/lib/api/errors'
import { deleteProject, type Project } from '@/lib/api/projects'
import { hasAnyRole, useAuthStore } from '@/stores/auth-store'
import { ProjectFormDialog } from './ProjectFormDialog'

/**
 * CU-23 Editar y eliminar proyecto. Las acciones solo se muestran a quien puede usarlas: el administrador
 * de la empresa o el propietario del proyecto. El backend vuelve a comprobarlo de todas formas.
 */
export function ProjectActions({ project }: { project: Project }) {
  const user = useAuthStore((s) => s.user)
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState(false)
  const [confirming, setConfirming] = useState(false)

  const canManage = hasAnyRole(user, ['COMPANY_ADMIN']) || user?.id === project.ownerId

  const remove = useMutation({
    mutationFn: () => deleteProject(project.id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['projects'] })
      toast.success(`Proyecto «${project.name}» eliminado.`)
    },
    onError: (error) => toast.error(errorMessage(error)),
  })

  if (!canManage) return null

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="sm" className="size-8 p-0" aria-label={`Acciones de ${project.name}`}>
            <MoreVertical className="size-4" aria-hidden />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem onSelect={() => setEditing(true)}>
            <Pencil className="size-4" aria-hidden />
            Editar
          </DropdownMenuItem>
          <DropdownMenuItem variant="destructive" onSelect={() => setConfirming(true)}>
            <Trash2 className="size-4" aria-hidden />
            Eliminar
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>

      {editing && (
        <ProjectFormDialog open project={project} onOpenChange={(open) => !open && setEditing(false)} />
      )}

      <AlertDialog open={confirming} onOpenChange={setConfirming}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>¿Eliminar el proyecto «{project.name}»?</AlertDialogTitle>
            <AlertDialogDescription>
              Se eliminarán también sus diagramas y, con ellos, las tareas, el código generado y las
              conversaciones con el asistente. Esta acción no se puede deshacer.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancelar</AlertDialogCancel>
            <AlertDialogAction onClick={() => remove.mutate()} disabled={remove.isPending}>
              Eliminar
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
