import { useEffect, useRef, useState } from 'react'
import type { SequenceContent } from '@/lib/diagram/types'

/** Flechas de Mermaid para cada tipo de mensaje. */
const ARROW: Record<string, string> = { SYNC: '->>', ASYNC: '-)', RETURN: '-->>', SELF: '->>' }

/** Escapa lo que Mermaid interpretaría como sintaxis. */
function safe(text: string): string {
  return text.replace(/[\n;#]/g, ' ').trim()
}

/** Traduce el `content_json` de secuencia (7.2) al lenguaje de Mermaid. */
export function toMermaid(content: SequenceContent): string {
  const alias = new Map(content.lifelines.map((lifeline, index) => [lifeline.id, `L${index}`]))
  const lines = ['sequenceDiagram', '  autonumber']

  for (const lifeline of content.lifelines) {
    // Una línea de vida sin clase es un actor externo (el usuario que inicia el flujo): se dibuja como un muñeco.
    // Las que representan una clase del diagrama van como caja, sin estereotipo: solo se trabaja con modelos.
    const kind = lifeline.classId ? 'participant' : 'actor'
    lines.push(`  ${kind} ${alias.get(lifeline.id)} as ${safe(lifeline.name)}`)
  }

  const ordered = [...content.messages].sort((a, b) => a.order - b.order)
  for (const message of ordered) {
    const from = alias.get(message.fromId)
    // Un mensaje a uno mismo vuelve a su propia línea de vida.
    const to = message.kind === 'SELF' ? from : alias.get(message.toId)
    if (!from || !to) continue
    lines.push(`  ${from}${ARROW[message.kind] ?? '->>'}${to}: ${safe(message.name)}`)
  }

  return lines.join('\n')
}

/** CU-11 / CU-20: render de solo lectura de un diagrama de secuencia con Mermaid. */
export function SequenceView({ content }: { content: SequenceContent }) {
  const container = useRef<HTMLDivElement>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    const definition = toMermaid(content)

    // Mermaid pesa bastante y solo hace falta en esta pantalla: se carga cuando se usa.
    import('mermaid')
      .then(async ({ default: mermaid }) => {
        // `mirrorActors: false` quita la segunda fila de cajas del final: los objetos solo se dibujan arriba.
        mermaid.initialize({
          startOnLoad: false,
          theme: 'neutral',
          // El tema neutro dibuja el muñeco y los bordes en un gris casi blanco: se oscurecen para que se lean.
          themeVariables: { actorBorder: '#4b5563' },
          securityLevel: 'strict',
          sequence: { mirrorActors: false },
        })
        const { svg } = await mermaid.render(`sequence-${Date.now()}`, definition)
        if (!cancelled && container.current) {
          container.current.innerHTML = svg
          setError(null)
        }
      })
      .catch(() => {
        if (!cancelled) setError('No se pudo dibujar el diagrama de secuencia.')
      })

    return () => {
      cancelled = true
    }
  }, [content])

  if (content.lifelines.length === 0) {
    return (
      <p className="rounded-xl border border-dashed py-16 text-center text-muted-foreground">
        El diagrama de secuencia está vacío.
      </p>
    )
  }

  return (
    <>
      {error && <p className="text-destructive">{error}</p>}
      <div ref={container} className="overflow-auto rounded-xl border bg-card p-4 [&_svg]:mx-auto" />
    </>
  )
}
