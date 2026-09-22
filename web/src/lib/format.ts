const DATE = new Intl.DateTimeFormat('es', { day: '2-digit', month: 'short', year: 'numeric' })

/** Fecha corta en español a partir de un instante ISO. */
export function formatDate(iso: string): string {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '—' : DATE.format(date)
}
