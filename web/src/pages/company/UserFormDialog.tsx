import { useMutation, useQueryClient } from '@tanstack/react-query'
import { LoaderCircle } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { FormError } from '@/components/FormError'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
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
import {
  ASSIGNABLE_ROLES,
  createUser,
  ROLE_LABEL,
  updateUser,
  type AssignableRole,
  type UserRequest,
} from '@/lib/api/users'
import type { User } from '@/lib/api/types'

interface Props {
  /** `null` da de alta; con un usuario, lo edita. */
  user: User | null
  open: boolean
  onOpenChange: (open: boolean) => void
}

/** CU-03: alta y edición de un usuario de la empresa. Los roles asignables excluyen `SOFTWARE_ADMIN`. */
export function UserFormDialog({ user, open, onOpenChange }: Props) {
  const queryClient = useQueryClient()
  const editing = user !== null

  const [username, setUsername] = useState(user?.username ?? '')
  const [email, setEmail] = useState(user?.email ?? '')
  const [fullName, setFullName] = useState(user?.fullName ?? '')
  const [password, setPassword] = useState('')
  const [roles, setRoles] = useState<AssignableRole[]>(
    (user?.roles ?? ['DESIGNER']).filter((role): role is AssignableRole =>
      ASSIGNABLE_ROLES.includes(role as AssignableRole),
    ),
  )

  const mutation = useMutation({
    mutationFn: (body: UserRequest) => (editing ? updateUser(user.id, body) : createUser(body)),
    onSuccess: async (saved) => {
      await queryClient.invalidateQueries({ queryKey: ['users'] })
      toast.success(editing ? `Usuario «${saved.username}» actualizado.` : `Usuario «${saved.username}» creado.`)
      onOpenChange(false)
    },
  })

  const toggleRole = (role: AssignableRole, checked: boolean) =>
    setRoles((current) => (checked ? [...current, role] : current.filter((r) => r !== role)))

  const submit = (event: FormEvent) => {
    event.preventDefault()
    mutation.mutate({
      username: username.trim(),
      email: email.trim(),
      fullName: fullName.trim(),
      password,
      roles,
    })
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{editing ? `Editar ${user.username}` : 'Nuevo usuario'}</DialogTitle>
          <DialogDescription>
            {editing
              ? 'Deje la contraseña vacía para mantener la actual.'
              : 'El usuario podrá iniciar sesión con el identificador de esta empresa.'}
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={submit} noValidate className="grid gap-4">
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="grid gap-2">
              <Label htmlFor="user-username">Usuario</Label>
              <Input
                id="user-username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                maxLength={50}
                required
                autoFocus
                disabled={mutation.isPending}
              />
            </div>

            <div className="grid gap-2">
              <Label htmlFor="user-email">Correo</Label>
              <Input
                id="user-email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                maxLength={100}
                required
                disabled={mutation.isPending}
              />
            </div>
          </div>

          <div className="grid gap-2">
            <Label htmlFor="user-fullname">Nombre completo</Label>
            <Input
              id="user-fullname"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              maxLength={100}
              disabled={mutation.isPending}
            />
          </div>

          <div className="grid gap-2">
            <Label htmlFor="user-password">Contraseña</Label>
            <Input
              id="user-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              minLength={editing ? undefined : 8}
              maxLength={100}
              required={!editing}
              aria-describedby="user-password-help"
              disabled={mutation.isPending}
            />
            <p id="user-password-help" className="text-xs text-muted-foreground">
              {editing ? 'Vacía = sin cambio. Si la escribe, mínimo 8 caracteres.' : 'Mínimo 8 caracteres.'}
            </p>
          </div>

          <fieldset className="grid gap-2">
            <legend className="mb-2 text-sm font-medium">Roles</legend>
            {ASSIGNABLE_ROLES.map((role) => (
              <div key={role} className="flex items-center gap-2">
                <Checkbox
                  id={`role-${role}`}
                  checked={roles.includes(role)}
                  onCheckedChange={(checked) => toggleRole(role, checked === true)}
                  disabled={mutation.isPending}
                />
                <Label htmlFor={`role-${role}`} className="font-normal">
                  {ROLE_LABEL[role]}
                </Label>
              </div>
            ))}
          </fieldset>

          <FormError error={mutation.error} />

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={mutation.isPending}>
              Cancelar
            </Button>
            <Button type="submit" disabled={mutation.isPending || roles.length === 0}>
              {mutation.isPending && <LoaderCircle className="size-4 animate-spin" aria-hidden />}
              {editing ? 'Guardar' : 'Crear'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
