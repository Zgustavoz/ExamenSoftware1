import { http } from './http'
import type { Page } from './types'

export interface Notification {
  id: string
  title: string
  message: string | null
  type: string | null
  payload: Record<string, unknown> | null
  read: boolean
  createdAt: string
}

/** CU-19. Solo las del usuario del token. */
export async function listNotifications(params: { unreadOnly?: boolean; page?: number } = {}): Promise<
  Page<Notification>
> {
  const { data } = await http.get<Page<Notification>>('/notifications', {
    params: { unreadOnly: params.unreadOnly ?? false, page: params.page ?? 0, size: 20 },
  })
  return data
}

export async function markNotificationRead(id: string): Promise<Notification> {
  const { data } = await http.patch<Notification>(`/notifications/${id}/read`)
  return data
}

/** Registra el token de FCM tras el login y en cada refresco (lo usa el móvil; en web queda disponible). */
export async function updateFcmToken(token: string): Promise<void> {
  await http.put('/me/fcm-token', { token })
}

export const NOTIFICATION_LABEL: Record<string, string> = {
  TASK_ASSIGNED: 'Tarea asignada',
  CODE_READY: 'Código listo',
  TASK_STATUS_CHANGED: 'Tarea actualizada',
}
