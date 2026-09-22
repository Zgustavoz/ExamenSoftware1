import { useMutation } from '@tanstack/react-query'
import { Building2, Eye, EyeOff, LoaderCircle, LogIn, TriangleAlert } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { login } from '@/lib/api/auth'
import { errorMessage } from '@/lib/api/errors'
import type { LoginResponse } from '@/lib/api/types'
import { homeFor } from '@/lib/roles'
import { useAuthStore } from '@/stores/auth-store'

/**
 * CU-01 Iniciar sesión. El `username` solo es único dentro de una empresa (D-01), así que el formulario pide
 * también el identificador de la empresa. Los mensajes de error los envía el backend ya en español.
 */
export default function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const saveSession = useAuthStore((s) => s.login)

  const [companySlug, setCompanySlug] = useState('')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)

  const mutation = useMutation({
    mutationFn: login,
    onSuccess: ({ token, user }: LoginResponse) => {
      saveSession(token, user)
      // Si la sesión caducó a mitad de camino, se vuelve a donde iba; si no, a la pantalla de su rol.
      const from = (location.state as { from?: string } | null)?.from
      navigate(from ?? homeFor(user), { replace: true })
    },
  })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    mutation.mutate({ companySlug: companySlug.trim(), username: username.trim(), password })
  }

  const pending = mutation.isPending

  return (
    <main className="flex min-h-svh items-center justify-center bg-linear-to-b from-secondary to-background p-6">
      <Card className="w-full max-w-sm">
        <CardHeader className="items-center text-center">
          <div className="mx-auto mb-2 flex size-11 items-center justify-center rounded-xl bg-primary/10">
            <Building2 className="size-6 text-primary" aria-hidden />
          </div>
          <CardTitle>
            <h1 className="text-xl">Plataforma de Diagramas UML</h1>
          </CardTitle>
          <CardDescription>Ingrese con los datos de su empresa.</CardDescription>
        </CardHeader>

        <CardContent>
          <form onSubmit={submit} noValidate className="grid gap-4">
            <div className="grid gap-2">
              <Label htmlFor="companySlug">Empresa</Label>
              <Input
                id="companySlug"
                name="companySlug"
                autoComplete="organization"
                placeholder="mi-empresa"
                required
                autoFocus
                value={companySlug}
                onChange={(e) => setCompanySlug(e.target.value)}
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
              <Label htmlFor="password">Contraseña</Label>
              <div className="relative">
                <Input
                  id="password"
                  name="password"
                  type={showPassword ? 'text' : 'password'}
                  autoComplete="current-password"
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
                <LogIn className="size-4" aria-hidden />
              )}
              {pending ? 'Ingresando…' : 'Ingresar'}
            </Button>

            <p className="text-center text-sm text-muted-foreground">
              ¿Su empresa todavía no está registrada?{' '}
              <Link to="/register-company" className="font-medium text-primary hover:underline">
                Registrar empresa
              </Link>
            </p>
          </form>
        </CardContent>
      </Card>
    </main>
  )
}
