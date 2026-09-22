import type { Diagram } from './diagrams'
import { gql } from './graphql'

export type InputType = 'TEXTO' | 'VOZ'

export interface AiMessage {
  role: 'user' | 'assistant'
  content: string
  timestamp?: string
}

export interface AiChat {
  id: string
  diagramId: string
  title: string | null
  messages: AiMessage[]
  createdAt: string
  updatedAt: string
}

export interface AiResult {
  explanation: string
  operations: unknown[]
  diagram: Diagram
}

const DIAGRAM_FIELDS = `id projectId name description type contentJson version sourceDiagramId createdBy createdAt updatedAt`

/** CU-13. El historial es por diagrama y por usuario: nadie ve las conversaciones de otro (D-16). */
export async function listAiChats(diagramId: string): Promise<AiChat[]> {
  const data = await gql<{ aiChats: AiChat[] }>(
    `query AiChats($diagramId: ID!) {
       aiChats(diagramId: $diagramId) { id diagramId title messages createdAt updatedAt }
     }`,
    { diagramId },
  )
  return data.aiChats
}

/**
 * CU-12. El backend envía la instrucción al `ai-service`, revalida lo que responde con el mismo applier del
 * editor y lo aplica. Si la IA falla, el diagrama queda intacto (CP-04).
 */
export async function sendAiInstruction(input: {
  diagramId: string
  instruction: string
  inputType: InputType
}): Promise<AiResult> {
  const data = await gql<{ sendAiInstruction: AiResult }>(
    `mutation SendAiInstruction($diagramId: ID!, $instruction: String!, $inputType: InputType!) {
       sendAiInstruction(diagramId: $diagramId, instruction: $instruction, inputType: $inputType) {
         explanation operations diagram { ${DIAGRAM_FIELDS} }
       }
     }`,
    input,
  )
  return data.sendAiInstruction
}

/** CU-12 paso 8. Es idempotente: los cambios ya se aplicaron al recibir la respuesta (D-07). */
export async function confirmAiChanges(diagramId: string): Promise<Diagram> {
  const data = await gql<{ confirmAiChanges: Diagram }>(
    `mutation ConfirmAiChanges($diagramId: ID!) { confirmAiChanges(diagramId: $diagramId) { ${DIAGRAM_FIELDS} } }`,
    { diagramId },
  )
  return data.confirmAiChanges
}
