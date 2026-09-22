import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bell, BellOff, Check, LoaderCircle } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { FormError } from '@/components/FormError'
import { PageHeader } from '@/components/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { errorMessage } from '@/lib/api/errors'
import { listNotifications, markNotificationRead, NOTIFICATION_LABEL } from '@/lib/api/notifications'
import { formatDate } from '@/lib/format'
import { cn } from '@/lib/utils'
import { PushSettings } from './PushSettings'

/** CU-19 Notificaciones: listado del usuario del token y marcar como leída. */
export default function NotificationsPage() {
  const queryClient = useQueryClient()
  const [unreadOnly, setUnreadOnly] = useState(false)

  const notifications = useQuery({
    queryKey: ['notifications', { unreadOnly }],
    queryFn: () => listNotifications({ unreadOnly }),
    placeholderData: keepPreviousData,
  })

  const markRead = useMutation({
    mutationFn: markNotificationRead,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['notifications'] }),
    onError: (error) => toast.error(errorMessage(error)),
  })

  const items = notifications.data?.content ?? []

  return (
    <>
      <PageHeader title="Notificaciones" description="Avisos de sus tareas y del código generado.">
        <Button variant="outline" onClick={() => setUnreadOnly((value) => !value)}>
          {unreadOnly ? <Bell className="size-4" aria-hidden /> : <BellOff className="size-4" aria-hidden />}
          {unreadOnly ? 'Ver todas' : 'Solo sin leer'}
        </Button>
      </PageHeader>

      <PushSettings />

      {notifications.isPending && (
        <p className="flex items-center gap-2 text-muted-foreground">
          <LoaderCircle className="size-4 animate-spin" aria-hidden />
          Cargando notificaciones…
        </p>
      )}

      {notifications.isError && <FormError error={notifications.error} />}

      {notifications.data &&
        (items.length === 0 ? (
          <p className="rounded-xl border border-dashed py-16 text-center text-muted-foreground">
            {unreadOnly ? 'No tiene notificaciones sin leer.' : 'Todavía no tiene notificaciones.'}
          </p>
        ) : (
          <ul className="grid gap-3">
            {items.map((notification) => (
              <li
                key={notification.id}
                className={cn(
                  'flex items-start justify-between gap-4 rounded-xl border p-4',
                  !notification.read && 'border-primary/40 bg-primary/5',
                )}
              >
                <div>
                  <p className="flex flex-wrap items-center gap-2 font-medium">
                    {notification.title}
                    {notification.type && (
                      <Badge variant="secondary">
                        {NOTIFICATION_LABEL[notification.type] ?? notification.type}
                      </Badge>
                    )}
                    {!notification.read && <Badge>Sin leer</Badge>}
                  </p>
                  {notification.message && (
                    <p className="mt-1 text-sm text-muted-foreground">{notification.message}</p>
                  )}
                  <p className="mt-1 text-xs text-muted-foreground">{formatDate(notification.createdAt)}</p>
                </div>

                {!notification.read && (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => markRead.mutate(notification.id)}
                    disabled={markRead.isPending}
                    aria-label={`Marcar como leída: ${notification.title}`}
                  >
                    <Check className="size-4" aria-hidden />
                    Marcar leída
                  </Button>
                )}
              </li>
            ))}
          </ul>
        ))}
    </>
  )
}
