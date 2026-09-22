import { TriangleAlert } from 'lucide-react'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { errorMessage } from '@/lib/api/errors'

/** Muestra el mensaje que envió el backend (ya en español). No se imprime nada más del error. */
export function FormError({ error }: { error: unknown }) {
  if (!error) return null
  return (
    <Alert variant="destructive" role="alert">
      <TriangleAlert className="size-4" aria-hidden />
      <AlertDescription>{errorMessage(error)}</AlertDescription>
    </Alert>
  )
}
