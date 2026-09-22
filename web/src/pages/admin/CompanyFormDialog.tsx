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
import { createCompany, slugify, updateCompany, type Company } from '@/lib/api/companies'

interface Props {
  /** `null` crea una empresa nueva; con una empresa, la edita. */
  company: Company | null
  open: boolean
  onOpenChange: (open: boolean) => void
}

/** CU-02: alta y edición de una empresa. El backend rechaza nombre o slug repetidos con `DUPLICATE_COMPANY`. */
export function CompanyFormDialog({ company, open, onOpenChange }: Props) {
  const queryClient = useQueryClient()
  const editing = company !== null

  const [name, setName] = useState(company?.name ?? '')
  const [slug, setSlug] = useState(company?.slug ?? '')
  // Mientras nadie escriba el slug a mano, se propone a partir del nombre.
  const [slugTouched, setSlugTouched] = useState(editing)

  const mutation = useMutation({
    mutationFn: (body: { name: string; slug: string }) =>
      editing ? updateCompany(company.id, body) : createCompany(body),
    onSuccess: async (saved) => {
      await queryClient.invalidateQueries({ queryKey: ['companies'] })
      toast.success(editing ? `Empresa «${saved.name}» actualizada.` : `Empresa «${saved.name}» creada.`)
      onOpenChange(false)
    },
  })

  const changeName = (value: string) => {
    setName(value)
    if (!slugTouched) setSlug(slugify(value))
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    mutation.mutate({ name: name.trim(), slug: slug.trim() })
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{editing ? 'Editar empresa' : 'Nueva empresa'}</DialogTitle>
          <DialogDescription>
            El identificador es lo que sus usuarios escribirán al iniciar sesión.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={submit} noValidate className="grid gap-4">
          <div className="grid gap-2">
            <Label htmlFor="company-name">Nombre</Label>
            <Input
              id="company-name"
              value={name}
              onChange={(e) => changeName(e.target.value)}
              maxLength={100}
              required
              autoFocus
              disabled={mutation.isPending}
            />
          </div>

          <div className="grid gap-2">
            <Label htmlFor="company-slug">Identificador</Label>
            <Input
              id="company-slug"
              value={slug}
              onChange={(e) => {
                setSlugTouched(true)
                setSlug(e.target.value)
              }}
              maxLength={50}
              required
              pattern="[a-z0-9]+(-[a-z0-9]+)*"
              aria-describedby="company-slug-help"
              disabled={mutation.isPending}
            />
            <p id="company-slug-help" className="text-xs text-muted-foreground">
              Minúsculas, números y guiones. Por ejemplo: mi-empresa
            </p>
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
