import { useMutation } from '@tanstack/react-query'
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
import { createCompanyAdmin, type Company } from '@/lib/api/companies'

interface Props {
  company: Company
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * CU-02: alta del administrador de una empresa. El `SOFTWARE_ADMIN` «administra los administradores de cada
 * empresa»; a partir de ahí es ese `COMPANY_ADMIN` quien gestiona al resto de usuarios (CU-03).
 */
export function CompanyAdminDialog({ company, open, onOpenChange }: Props) {
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [fullName, setFullName] = useState('')

  const mutation = useMutation({
    mutationFn: () =>
      createCompanyAdmin(company.id, {
        username: username.trim(),
        email: email.trim(),
        password,
        fullName: fullName.trim(),
      }),
    onSuccess: (created) => {
      toast.success(`Administrador «${created.username}» creado en ${company.name}.`)
      onOpenChange(false)
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
          <DialogTitle>Administrador de {company.name}</DialogTitle>
          <DialogDescription>
            Podrá gestionar los usuarios y proyectos de esta empresa. Iniciará sesión con el identificador
            «{company.slug}».
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={submit} noValidate className="grid gap-4">
          <div className="grid gap-2">
            <Label htmlFor="admin-username">Usuario</Label>
            <Input
              id="admin-username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              maxLength={50}
              required
              autoFocus
              disabled={mutation.isPending}
            />
          </div>

          <div className="grid gap-2">
            <Label htmlFor="admin-email">Correo</Label>
            <Input
              id="admin-email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              maxLength={100}
              required
              disabled={mutation.isPending}
            />
          </div>

          <div className="grid gap-2">
            <Label htmlFor="admin-fullname">Nombre completo</Label>
            <Input
              id="admin-fullname"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              maxLength={100}
              disabled={mutation.isPending}
            />
          </div>

          <div className="grid gap-2">
            <Label htmlFor="admin-password">Contraseña</Label>
            <Input
              id="admin-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              minLength={8}
              maxLength={100}
              required
              aria-describedby="admin-password-help"
              disabled={mutation.isPending}
            />
            <p id="admin-password-help" className="text-xs text-muted-foreground">
              Mínimo 8 caracteres.
            </p>
          </div>

          <FormError error={mutation.error} />

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={mutation.isPending}>
              Cancelar
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending && <LoaderCircle className="size-4 animate-spin" aria-hidden />}
              Crear administrador
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
