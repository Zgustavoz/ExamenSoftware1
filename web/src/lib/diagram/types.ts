/** Esquema de `content_json` (sección 7.2). Es el mismo que valida el backend. */

export const VISIBILITIES = ['PUBLIC', 'PRIVATE', 'PROTECTED', 'PACKAGE'] as const
export type Visibility = (typeof VISIBILITIES)[number]

export const STEREOTYPES = ['interface', 'abstract', 'enum'] as const
export type Stereotype = (typeof STEREOTYPES)[number]

export const RELATIONSHIP_TYPES = [
  'ASSOCIATION',
  'AGGREGATION',
  'COMPOSITION',
  'GENERALIZATION',
  'REALIZATION',
  'DEPENDENCY',
] as const
export type RelationshipType = (typeof RELATIONSHIP_TYPES)[number]

/** Tipos que acepta el applier, además del nombre de otra clase y `List<T>` / `Set<T>` de estos. */
export const DATA_TYPES = [
  'String',
  'int',
  'Integer',
  'long',
  'Long',
  'double',
  'Double',
  'float',
  'boolean',
  'Boolean',
  'UUID',
  'LocalDate',
  'LocalDateTime',
  'BigDecimal',
] as const

export interface Parameter {
  name: string
  type: string
}

export interface Attribute {
  id: string
  name: string
  type: string
  visibility: Visibility
}

export interface Method {
  id: string
  name: string
  returnType: string
  visibility: Visibility
  parameters: Parameter[]
}

export interface UmlClass {
  id: string
  name: string
  stereotype: Stereotype | null
  visibility: Visibility
  x: number
  y: number
  attributes: Attribute[]
  methods: Method[]
}

export interface Relationship {
  id: string
  type: RelationshipType
  sourceId: string
  targetId: string
  sourceMultiplicity?: string | null
  targetMultiplicity?: string | null
  sourceRole?: string | null
  targetRole?: string | null
  name?: string | null
}

export interface ClassContent {
  schemaVersion: number
  type: 'CLASS'
  classes: UmlClass[]
  relationships: Relationship[]
}

export interface Lifeline {
  id: string
  name: string
  classId?: string | null
}

export const MESSAGE_KINDS = ['SYNC', 'ASYNC', 'RETURN', 'SELF'] as const
export type MessageKind = (typeof MESSAGE_KINDS)[number]

export interface SequenceMessage {
  id: string
  order: number
  fromId: string
  toId: string
  name: string
  kind: MessageKind
}

export interface SequenceContent {
  schemaVersion: number
  type: 'SEQUENCE'
  lifelines: Lifeline[]
  messages: SequenceMessage[]
}

export type DiagramContent = ClassContent | SequenceContent

export const EMPTY_CLASS_CONTENT: ClassContent = {
  schemaVersion: 1,
  type: 'CLASS',
  classes: [],
  relationships: [],
}

export function isClassContent(content: DiagramContent): content is ClassContent {
  return content.type === 'CLASS'
}

/** Límites que impone el applier a las posiciones (`OUT_OF_BOUNDS`). */
export const CANVAS_MIN = 0
export const CANVAS_MAX = 10000

export function clampToCanvas(value: number): number {
  return Math.min(CANVAS_MAX, Math.max(CANVAS_MIN, Math.round(value)))
}

/** Formato de multiplicidad que acepta el backend. */
export const MULTIPLICITY_PATTERN = /^(\d+|\*)(\.\.(\d+|\*))?$/

export const VISIBILITY_SYMBOL: Record<Visibility, string> = {
  PUBLIC: '+',
  PRIVATE: '−',
  PROTECTED: '#',
  PACKAGE: '~',
}

export const RELATIONSHIP_LABEL: Record<RelationshipType, string> = {
  ASSOCIATION: 'Asociación',
  AGGREGATION: 'Agregación',
  COMPOSITION: 'Composición',
  GENERALIZATION: 'Herencia',
  REALIZATION: 'Realización',
  DEPENDENCY: 'Dependencia',
}

export const VISIBILITY_LABEL: Record<Visibility, string> = {
  PUBLIC: 'Pública',
  PRIVATE: 'Privada',
  PROTECTED: 'Protegida',
  PACKAGE: 'De paquete',
}

/** Firma legible de un método: `nombre(p: T, …): R`. */
export function methodSignature(method: Method): string {
  const params = method.parameters.map((p) => `${p.name}: ${p.type}`).join(', ')
  return `${method.name}(${params}): ${method.returnType}`
}
