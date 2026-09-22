import { useEffect, useState } from 'react'

/** Devuelve el valor tras `delay` ms sin cambios. Evita una consulta por cada tecla al filtrar. */
export function useDebounced<T>(value: T, delay = 300): T {
  const [debounced, setDebounced] = useState(value)

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay)
    return () => clearTimeout(timer)
  }, [value, delay])

  return debounced
}
