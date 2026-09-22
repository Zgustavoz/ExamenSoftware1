import type { Parameter } from './types'

/** Texto «nombre: tipo, nombre: tipo» ⇄ lista de parámetros de un método. */
export function parseParameters(text: string): Parameter[] {
  return text
    .split(',')
    .map((part) => part.trim())
    .filter(Boolean)
    .map((part) => {
      const [name, type] = part.split(':').map((s) => s.trim())
      return { name: name || 'param', type: type || 'String' }
    })
}

export function formatParameters(parameters: Parameter[]): string {
  return parameters.map((p) => `${p.name}: ${p.type}`).join(', ')
}
