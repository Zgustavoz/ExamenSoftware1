import { useMutation, useQueryClient } from '@tanstack/react-query'
import { LoaderCircle, Upload } from 'lucide-react'
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
import { ApiError } from '@/lib/api/errors'
import { importXmi, xmiFileProblem, XMI_EXTENSIONS } from '@/lib/api/xmi'

interface Props {
  projectId: string
  open: boolean
  onOpenChange: (open: boolean) => void
}

/** CU-16 Importar XMI: el archivo se convierte a `content_json` y se persiste como un diagrama nuevo. */
export function XmiImportDialog({ projectId, open, onOpenChange }: Props) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [file, setFile] = useState<File | null>(null)
  const [problem, setProblem] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => importXmi(projectId, file!),
    onSuccess: async (diagram) => {
      await queryClient.invalidateQueries({ queryKey: ['diagrams', projectId] })
      toast.success(`Diagrama «${diagram.name}» importado.`)
      onOpenChange(false)
      navigate(`/diagrams/${diagram.id}`)
    },
  })

  const chooseFile = (chosen: File | null) => {
    setFile(chosen)
    setProblem(chosen ? xmiFileProblem(chosen) : null)
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!file || problem) return
    mutation.mutate()
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Importar XMI</DialogTitle>
          <DialogDescription>
            Se creará un diagrama nuevo en este proyecto. Acepta XMI de Enterprise Architect, hasta 5 MB.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={submit} noValidate className="grid gap-4">
          <div className="grid gap-2">
            <Label htmlFor="xmi-file">Archivo</Label>
            <Input
              id="xmi-file"
              type="file"
              accept={XMI_EXTENSIONS.join(',')}
              onChange={(e) => chooseFile(e.target.files?.[0] ?? null)}
              disabled={mutation.isPending}
            />
            {problem && (
              <p role="alert" className="text-xs text-destructive">
                {problem}
              </p>
            )}
          </div>

          {/* Lo que el adaptador no pueda mapear vuelve como XMI_INVALID con el detalle. */}
          <FormError error={mutation.error instanceof ApiError ? mutation.error : null} />

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={mutation.isPending}>
              Cancelar
            </Button>
            <Button type="submit" disabled={!file || problem !== null || mutation.isPending}>
              {mutation.isPending ? (
                <LoaderCircle className="size-4 animate-spin" aria-hidden />
              ) : (
                <Upload className="size-4" aria-hidden />
              )}
              Importar
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
