import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/lib/api/errors'

/** No se reintenta lo que el servidor ya rechazó (4xx): repetirlo no lo va a arreglar. */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      refetchOnWindowFocus: false,
      retry: (failureCount, error) =>
        !(error instanceof ApiError && error.status !== null && error.status < 500) && failureCount < 2,
    },
  },
})
