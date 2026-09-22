import { gql } from './graphql'
import type { DiagramContent } from '@/lib/diagram/types'

export interface Diagram {
  id: string
  projectId: string
  name: string
  description: string | null
  type: 'CLASS' | 'SEQUENCE'
  contentJson: DiagramContent
  version: number
  sourceDiagramId: string | null
  createdBy: string | null
  createdAt: string
  updatedAt: string
}

const DIAGRAM_FIELDS = `
  id projectId name description type contentJson version sourceDiagramId createdBy createdAt updatedAt
`

/** CU-05/11: diagramas de un proyecto de la empresa del token. */
export async function listDiagrams(projectId: string): Promise<Diagram[]> {
  const data = await gql<{ diagrams: Diagram[] }>(
    `query Diagrams($projectId: ID!) { diagrams(projectId: $projectId) { ${DIAGRAM_FIELDS} } }`,
    { projectId },
  )
  return data.diagrams
}

/** CU-11. Devuelve `null` si no existe o es de otra empresa. */
export async function getDiagram(id: string): Promise<Diagram | null> {
  const data = await gql<{ diagram: Diagram | null }>(
    `query Diagram($id: ID!) { diagram(id: $id) { ${DIAGRAM_FIELDS} } }`,
    { id },
  )
  return data.diagram
}

/** CU-06. El backend crea el `content_json` inicial y `version = 1`. */
export async function createDiagram(input: {
  projectId: string
  name: string
  description: string
}): Promise<Diagram> {
  const data = await gql<{ createDiagram: Diagram }>(
    `mutation CreateDiagram($projectId: ID!, $name: String!, $description: String) {
       createDiagram(projectId: $projectId, name: $name, description: $description) { ${DIAGRAM_FIELDS} }
     }`,
    input,
  )
  return data.createDiagram
}

/**
 * CU-10. Guardado con control optimista: si `baseVersion` no coincide con la del servidor, la mutación falla
 * con `VERSION_CONFLICT` y `details` trae `{currentVersion, contentJson}` para recargar.
 */
export async function saveDiagram(input: {
  id: string
  contentJson: DiagramContent
  baseVersion: number
}): Promise<Diagram> {
  const data = await gql<{ saveDiagram: Diagram }>(
    `mutation SaveDiagram($id: ID!, $contentJson: JSON!, $baseVersion: Int!) {
       saveDiagram(id: $id, contentJson: $contentJson, baseVersion: $baseVersion) { ${DIAGRAM_FIELDS} }
     }`,
    input,
  )
  return data.saveDiagram
}

/** Estado del servidor que acompaña a un `VERSION_CONFLICT`. */
export interface VersionConflict {
  currentVersion: number
  contentJson: DiagramContent
}

export function versionConflictDetails(details: Record<string, unknown>): VersionConflict | null {
  const { currentVersion, contentJson } = details
  if (typeof currentVersion !== 'number' || typeof contentJson !== 'object' || contentJson === null) return null
  return { currentVersion, contentJson: contentJson as DiagramContent }
}

/**
 * CU-20. Analiza el diagrama de clases con la IA y crea un diagrama `SEQUENCE` nuevo, con
 * `source_diagram_id` apuntando al de origen.
 */
export async function generateSequenceDiagram(sourceDiagramId: string): Promise<Diagram> {
  const data = await gql<{ generateSequenceDiagram: Diagram }>(
    `mutation GenerateSequence($sourceDiagramId: ID!) {
       generateSequenceDiagram(sourceDiagramId: $sourceDiagramId) { ${DIAGRAM_FIELDS} }
     }`,
    { sourceDiagramId },
  )
  return data.generateSequenceDiagram
}
