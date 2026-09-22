import { BellRing, Check, LoaderCircle } from 'lucide-react'
import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { disablePush, enablePush, getPushStatus, PushError, type PushStatus } from '@/lib/push/push'

/** Texto de los estados en los que el usuario no puede hacer nada desde aquí. */
const INFO: Partial<Record<PushStatus, string>> = {
  'not-configured': 'Los avisos push no están configurados en este entorno.',
  unsupported: 'Este navegador no admite avisos push.',
  denied: 'Los avisos están bloqueados. Puede permitirlos desde los ajustes del sitio en su navegador.',
}

/** Activa o desactiva los avisos push de este navegador (Firebase Cloud Messaging). */
export function PushSettings() {
  const [status, setStatus] = useState<PushStatus | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let active = true
    void getPushStatus().then((value) => active && setStatus(value))
    return () => {
      active = false
    }
  }, [])

  const enable = async () => {
    setBusy(true)
    try {
      await enablePush()
      setStatus('enabled')
      toast.success('Avisos activados en este navegador.')
    } catch (error) {
      if (error instanceof PushError && error.reason !== 'failed') setStatus(error.reason)
      toast.error(error instanceof Error ? error.message : 'No se pudieron activar los avisos.')
    } finally {
      setBusy(false)
    }
  }

  const disable = async () => {
    setBusy(true)
    await disablePush()
    setStatus('prompt')
    setBusy(false)
    toast.success('Avisos desactivados en este navegador.')
  }

  if (status === null) return null

  return (
    <div className="mb-6 flex flex-wrap items-center justify-between gap-3 rounded-xl border bg-card p-4">
      <p className="flex items-center gap-2 text-sm text-muted-foreground">
        {status === 'enabled' ? (
          <Check className="size-4 text-primary" aria-hidden />
        ) : (
          <BellRing className="size-4" aria-hidden />
        )}
        {status === 'enabled'
          ? 'Recibirá un aviso en este navegador cuando haya novedades.'
          : (INFO[status] ?? 'Reciba un aviso en este navegador cuando haya novedades, incluso con la pestaña cerrada.')}
      </p>

      {status === 'prompt' && (
        <Button onClick={enable} disabled={busy}>
          {busy ? <LoaderCircle className="size-4 animate-spin" aria-hidden /> : <BellRing className="size-4" aria-hidden />}
          Activar avisos
        </Button>
      )}
      {status === 'enabled' && (
        <Button variant="outline" onClick={disable} disabled={busy}>
          Desactivar
        </Button>
      )}
    </div>
  )
}
