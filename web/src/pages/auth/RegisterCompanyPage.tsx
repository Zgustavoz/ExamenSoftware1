import { useMutation } from '@tanstack/react-query'
import { Building2, Eye, EyeOff, LoaderCircle, TriangleAlert, UserPlus } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { registerCompany } from '@/lib/api/auth'
import { errorMessage } from '@/lib/api/errors'
import type { LoginResponse } from '@/lib/api/types'
import { useAuthStore } from '@/stores/auth-store'

/** El identificador se usa para iniciar sesión, así que se propone a partir del nombre de la empresa. */
function slugFrom(name: string): string {
  return name
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '') // quita los acentos
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 50)
}

/**
 * CU-22 Registrar empresa. Alta pública: crea la empresa y su primer administrador, y deja la sesión
 * iniciada, así que al terminar se entra directamente a administrar los usuarios (CU-03).
 */
export default function RegisterCompanyPage() {
  const navigate = useNavigate()
  const saveSession = useAuthStore((s) => s.login)

  const [companyName, setCompanyName] = useState('')
  const [companySlug, setCompanySlug] = useState('')
  const [slugEditado, setSlugEditado] = useState(false)
  const [fullName, setFullName] = useState('')
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)

  const mutation = useMutation({
    mutationFn: registerCompany,
    onSuccess: ({ token, user }: LoginResponse) => {
      saveSession(token, user)
      navigate('/company/users', { replace: true })
    },
  })

  const onCompanyName = (value: string) => {
    setCompanyName(value)
    if (!slugEditado) setCompanySlug(slugFrom(value))
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    mutation.mutate({
      companyName: companyName.trim(),
      companySlug: companySlug.trim(),
      admin: { fullName: fullName.trim(), username: username.trim(), email: email.trim(), password },
    })
  }

  const pending = mutation.isPending

  return (
    <main className="flex min-h-svh items-center justify-center bg-linear-to-b from-secondary to-background p-6">
      <Card className="w-full max-w-md">
        <CardHeader className="items-center text-center">
          <div className="mx-auto mb-2 flex size-11 items-center justify-center rounded-xl bg-primary/10">
            <Building2 className="size-6 text-primary" aria-hidden />
          </div>
          <CardTitle>
            <h1 className="text-xl">Registrar empresa</h1>
          </CardTitle>
          <CardDescription>Cree su empresa y la cuenta de administrador.</CardDescription>
        </CardHeader>

        <CardContent>
          <form onSubmit={submit} noValidate className="grid gap-4">
            <div className="grid gap-2">
              <Label htmlFor="companyName">Nombre de la empresa</Label>
              <Input
                id="companyName"
                name="companyName"
                required
                autoFocus
                value={companyName}
                onChange={(e) => onCompanyName(e.target.value)}
                disabled={pending}
              />
            </div>

            <div className="grid gap-2">
              <Label htmlFor="companySlug">Identificador</Label>
              <Input
                id="companySlug"
                name="companySlug"
                required
                placeholder="mi-empresa"
                value={companySlug}
                onChange={(e) => {
                  setSlugEditado(true)
                  setCompanySlug(e.target.value)
                }}
                disabled={pending}
              />
              <p className="text-xs text-muted-foreground">
                Con este identificador iniciarán sesión los usuarios. Minúsculas, números y guiones.
              </p>
            </div>

            <hr className="my-1" />

            <div className="grid gap-2">
              <Label htmlFor="fullName">Su nombre</Label>
              <Input
                id="fullName"
                name="fullName"
                autoComplete="name"
                value={fullName}
                onChange={(e) => setFullName(e.target.value)}
                disabled={pending}
              />
            </div>

            <div className="grid gap-2">
              <Label htmlFor="username">Usuario</Label>
              <Input
                id="username"
                name="username"
                autoComplete="username"
                required
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                disabled={pending}
              />
            </div>

            <div className="grid gap-2">
              <Label htmlFor="email">Correo</Label>
              <Input
                id="email"
                name="email"
                type="email"
                autoComplete="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                disabled={pending}
              />
            </div>

            <div className="grid gap-2">
              <Label htmlFor="password">Contraseña</Label>
              <div className="relative">
                <Input
                  id="password"
                  name="password"
                  type={showPassword ? 'text' : 'password'}
                  autoComplete="new-password"
                  required
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  disabled={pending}
                  className="pr-10"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword((v) => !v)}
                  aria-label={showPassword ? 'Ocultar contraseña' : 'Mostrar contraseña'}
                  className="absolute inset-y-0 right-0 flex w-10 items-center justify-center text-muted-foreground hover:text-foreground"
                >
                  {showPassword ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                </button>
              </div>
              <p className="text-xs text-muted-foreground">Mínimo 8 caracteres.</p>
            </div>

            {mutation.isError && (
              <Alert variant="destructive" role="alert">
                <TriangleAlert className="size-4" aria-hidden />
                <AlertDescription>{errorMessage(mutation.error)}</AlertDescription>
              </Alert>
            )}

            <Button type="submit" className="mt-1 w-full" disabled={pending}>
              {pending ? (
                <LoaderCircle className="size-4 animate-spin" aria-hidden />
              ) : (
                <UserPlus className="size-4" aria-hidden />
              )}
              {pending ? 'Registrando…' : 'Registrar empresa'}
            </Button>

            <p className="text-center text-sm text-muted-foreground">
              ¿Ya tiene una cuenta?{' '}
              <Link to="/login" className="font-medium text-primary hover:underline">
                Iniciar sesión
              </Link>
            </p>
          </form>
        </CardContent>
      </Card>
    </main>
  )
}
