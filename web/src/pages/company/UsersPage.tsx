import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Ban, CheckCircle2, LoaderCircle, MoreHorizontal, Pencil, Plus } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { FormError } from '@/components/FormError'
import { PageHeader } from '@/components/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { errorMessage } from '@/lib/api/errors'
import type { User } from '@/lib/api/types'
import { listUsers, ROLE_LABEL, setUserActive } from '@/lib/api/users'
import { useAuthStore } from '@/stores/auth-store'
import { UserFormDialog } from './UserFormDialog'

/** CU-03 Gestionar usuarios de empresa. El backend limita el listado a la empresa del token. */
export default function UsersPage() {
  const queryClient = useQueryClient()
  const currentUserId = useAuthStore((s) => s.user?.id)
  const users = useQuery({ queryKey: ['users'], queryFn: listUsers })

  const [editing, setEditing] = useState<User | null | undefined>(undefined)

  const toggle = useMutation({
    mutationFn: ({ id, active }: { id: string; active: boolean }) => setUserActive(id, active),
    onSuccess: async (user) => {
      await queryClient.invalidateQueries({ queryKey: ['users'] })
      toast.success(user.active ? `«${user.username}» activado.` : `«${user.username}» desactivado.`)
    },
    onError: (error) => toast.error(errorMessage(error)),
  })

  return (
    <>
      <PageHeader title="Usuarios de la empresa" description="Alta, edición y estado de los usuarios.">
        <Button onClick={() => setEditing(null)}>
          <Plus className="size-4" aria-hidden />
          Nuevo usuario
        </Button>
      </PageHeader>

      {users.isPending && (
        <p className="flex items-center gap-2 text-muted-foreground">
          <LoaderCircle className="size-4 animate-spin" aria-hidden />
          Cargando usuarios…
        </p>
      )}

      {users.isError && <FormError error={users.error} />}

      {users.isSuccess &&
        (users.data.length === 0 ? (
          <p className="rounded-xl border border-dashed py-16 text-center text-muted-foreground">
            Todavía no hay usuarios en la empresa.
          </p>
        ) : (
          <div className="overflow-hidden rounded-xl border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Usuario</TableHead>
                  <TableHead>Nombre</TableHead>
                  <TableHead>Correo</TableHead>
                  <TableHead>Roles</TableHead>
                  <TableHead>Estado</TableHead>
                  <TableHead className="w-12" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {users.data.map((user) => {
                  const isSelf = user.id === currentUserId
                  return (
                    <TableRow key={user.id}>
                      <TableCell className="font-medium">{user.username}</TableCell>
                      <TableCell className="text-muted-foreground">{user.fullName ?? '—'}</TableCell>
                      <TableCell className="text-muted-foreground">{user.email}</TableCell>
                      <TableCell>
                        <span className="flex flex-wrap gap-1">
                          {user.roles.map((role) => (
                            <Badge key={role} variant="secondary">
                              {ROLE_LABEL[role]}
                            </Badge>
                          ))}
                        </span>
                      </TableCell>
                      <TableCell>
                        <Badge variant={user.active ? 'default' : 'secondary'}>
                          {user.active ? 'Activo' : 'Inactivo'}
                        </Badge>
                      </TableCell>
                      <TableCell>
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild>
                            <Button variant="ghost" size="icon" aria-label={`Acciones de ${user.username}`}>
                              <MoreHorizontal className="size-4" aria-hidden />
                            </Button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent align="end">
                            <DropdownMenuItem onSelect={() => setEditing(user)}>
                              <Pencil className="size-4" aria-hidden />
                              Editar
                            </DropdownMenuItem>
                            {/* El backend no deja desactivarse a uno mismo; aquí ni siquiera se ofrece. */}
                            {!isSelf && (
                              <DropdownMenuItem
                                onSelect={() => toggle.mutate({ id: user.id, active: !user.active })}
                              >
                                {user.active ? (
                                  <Ban className="size-4" aria-hidden />
                                ) : (
                                  <CheckCircle2 className="size-4" aria-hidden />
                                )}
                                {user.active ? 'Desactivar' : 'Activar'}
                              </DropdownMenuItem>
                            )}
                          </DropdownMenuContent>
                        </DropdownMenu>
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </div>
        ))}

      {editing !== undefined && (
        <UserFormDialog
          key={editing?.id ?? 'new'}
          user={editing}
          open
          onOpenChange={(open) => !open && setEditing(undefined)}
        />
      )}
    </>
  )
}
