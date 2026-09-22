import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Ban, CheckCircle2, LoaderCircle, MoreHorizontal, Pencil, Plus, UserPlus } from 'lucide-react'
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
import { listCompanies, setCompanyActive, type Company } from '@/lib/api/companies'
import { errorMessage } from '@/lib/api/errors'
import { formatDate } from '@/lib/format'
import { CompanyAdminDialog } from './CompanyAdminDialog'
import { CompanyFormDialog } from './CompanyFormDialog'

/** CU-02 Gestionar empresas. Solo accesible al `SOFTWARE_ADMIN`. */
export default function CompaniesPage() {
  const queryClient = useQueryClient()
  const companies = useQuery({ queryKey: ['companies'], queryFn: listCompanies })

  // `undefined` mantiene el diálogo cerrado; `null` abre el de alta y una empresa abre el de edición.
  const [editing, setEditing] = useState<Company | null | undefined>(undefined)
  const [addingAdminTo, setAddingAdminTo] = useState<Company | undefined>(undefined)

  const toggle = useMutation({
    mutationFn: ({ id, active }: { id: string; active: boolean }) => setCompanyActive(id, active),
    onSuccess: async (company) => {
      await queryClient.invalidateQueries({ queryKey: ['companies'] })
      toast.success(company.active ? `«${company.name}» activada.` : `«${company.name}» desactivada.`)
    },
    onError: (error) => toast.error(errorMessage(error)),
  })

  return (
    <>
      <PageHeader title="Empresas" description="Alta, edición y estado de las empresas de la plataforma.">
        <Button onClick={() => setEditing(null)}>
          <Plus className="size-4" aria-hidden />
          Nueva empresa
        </Button>
      </PageHeader>

      {companies.isPending && (
        <p className="flex items-center gap-2 text-muted-foreground">
          <LoaderCircle className="size-4 animate-spin" aria-hidden />
          Cargando empresas…
        </p>
      )}

      {companies.isError && <FormError error={companies.error} />}

      {companies.isSuccess &&
        (companies.data.length === 0 ? (
          <p className="rounded-xl border border-dashed py-16 text-center text-muted-foreground">
            Todavía no hay empresas registradas.
          </p>
        ) : (
          <div className="overflow-hidden rounded-xl border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Nombre</TableHead>
                  <TableHead>Identificador</TableHead>
                  <TableHead>Estado</TableHead>
                  <TableHead>Creada</TableHead>
                  <TableHead className="w-12" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {companies.data.map((company) => (
                  <TableRow key={company.id}>
                    <TableCell className="font-medium">{company.name}</TableCell>
                    <TableCell className="text-muted-foreground">{company.slug}</TableCell>
                    <TableCell>
                      <Badge variant={company.active ? 'default' : 'secondary'}>
                        {company.active ? 'Activa' : 'Inactiva'}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-muted-foreground">{formatDate(company.createdAt)}</TableCell>
                    <TableCell>
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="icon" aria-label={`Acciones de ${company.name}`}>
                            <MoreHorizontal className="size-4" aria-hidden />
                          </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end">
                          <DropdownMenuItem onSelect={() => setEditing(company)}>
                            <Pencil className="size-4" aria-hidden />
                            Editar
                          </DropdownMenuItem>
                          <DropdownMenuItem onSelect={() => setAddingAdminTo(company)}>
                            <UserPlus className="size-4" aria-hidden />
                            Crear administrador
                          </DropdownMenuItem>
                          <DropdownMenuItem
                            onSelect={() => toggle.mutate({ id: company.id, active: !company.active })}
                          >
                            {company.active ? (
                              <Ban className="size-4" aria-hidden />
                            ) : (
                              <CheckCircle2 className="size-4" aria-hidden />
                            )}
                            {company.active ? 'Desactivar' : 'Activar'}
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        ))}

      {editing !== undefined && (
        // La clave reinicia el formulario al cambiar de empresa, para que no arrastre lo escrito antes.
        <CompanyFormDialog
          key={editing?.id ?? 'new'}
          company={editing}
          open
          onOpenChange={(open) => !open && setEditing(undefined)}
        />
      )}

      {addingAdminTo && (
        <CompanyAdminDialog
          key={addingAdminTo.id}
          company={addingAdminTo}
          open
          onOpenChange={(open) => !open && setAddingAdminTo(undefined)}
        />
      )}
    </>
  )
}
