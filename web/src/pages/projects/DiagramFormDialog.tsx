import { useMutation, useQueryClient } from '@tanstack/react-query'
import { LoaderCircle } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
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
import { createDiagram } from '@/lib/api/diagrams'

interface Props {
  projectId: string
  open: boolean
  onOpenChange: (open: boolean) => void
}

/** CU-06 Crear diagrama de clases. El nombre no puede repetirse en el proyecto (`DUPLICATE_DIAGRAM`). */
export function DiagramFormDialog({ projectId, open, onOpenChange }: Props) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')

  const mutation = useMutation({
    mutationFn: () => createDiagram({ projectId, name: name.trim(), description: description.trim() }),
    onSuccess: async (diagram) => {
      await queryClient.invalidateQueries({ queryKey: ['diagrams', projectId] })
      toast.success(`Diagrama «${diagram.name}» creado.`)
      onOpenChange(false)
      // CU-06 termina abriendo el editor con el diagrama recién creado.
      navigate(`/diagrams/${diagram.id}`)
    },
  })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    mutation.mutate()
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Nuevo diagrama de clases</DialogTitle>
          <DialogDescription>Se creará vacío y se abrirá en el editor.</DialogDescription>
        </DialogHeader>

        <form onSubmit={submit} noValidate className="grid gap-4">
          <div className="grid gap-2">
            <Label htmlFor="diagram-name">Nombre</Label>
            <Input
              id="diagram-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              maxLength={100}
              required
              autoFocus
              disabled={mutation.isPending}
            />
          </div>

          <div className="grid gap-2">
            <Label htmlFor="diagram-description">Descripción</Label>
            <textarea
              id="diagram-description"
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
              Crear
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
