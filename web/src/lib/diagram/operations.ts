import type { Attribute, Method, Relationship, Stereotype, UmlClass, Visibility } from './types'

/**
 * Vocabulario de operaciones (sección 7.3). Es el mismo para el editor manual, el canal de colaboración y la
 * IA: el servidor las valida todas con el `DiagramOperationApplier`, así que el cliente nunca modifica el
 * `content_json` por su cuenta.
 */
export type DiagramOperation =
  | { op: 'ADD_CLASS'; class: NewClass }
  | { op: 'UPDATE_CLASS'; classId: string; changes: ClassChanges }
  | { op: 'MOVE_CLASS'; classId: string; x: number; y: number }
  | { op: 'REMOVE_CLASS'; classId: string }
  | { op: 'ADD_ATTRIBUTE'; classId: string; attribute: NewAttribute }
  | { op: 'UPDATE_ATTRIBUTE'; classId: string; attributeId: string; changes: Partial<NewAttribute> }
  | { op: 'REMOVE_ATTRIBUTE'; classId: string; attributeId: string }
  | { op: 'ADD_METHOD'; classId: string; method: NewMethod }
  | { op: 'UPDATE_METHOD'; classId: string; methodId: string; changes: Partial<NewMethod> }
  | { op: 'REMOVE_METHOD'; classId: string; methodId: string }
  | { op: 'ADD_RELATIONSHIP'; relationship: NewRelationship }
  | { op: 'UPDATE_RELATIONSHIP'; relationshipId: string; changes: RelationshipChanges }
  | { op: 'REMOVE_RELATIONSHIP'; relationshipId: string }

export interface NewClass {
  name: string
  stereotype?: Stereotype | null
  visibility?: Visibility
  x?: number
  y?: number
  attributes?: NewAttribute[]
  methods?: NewMethod[]
}

export interface ClassChanges {
  name?: string
  stereotype?: Stereotype | null
  visibility?: Visibility
}

export type NewAttribute = Omit<Attribute, 'id'>
export type NewMethod = Omit<Method, 'id'>
export type NewRelationship = Omit<Relationship, 'id'>
export type RelationshipChanges = Partial<Omit<Relationship, 'id' | 'sourceId' | 'targetId'>>

/** El id que la operación afecta, para pedir su bloqueo antes de enviarla (CU-17). */
export function targetElementId(operation: DiagramOperation): string | undefined {
  switch (operation.op) {
    case 'ADD_CLASS':
    case 'ADD_RELATIONSHIP':
      return undefined
    case 'UPDATE_RELATIONSHIP':
    case 'REMOVE_RELATIONSHIP':
      return operation.relationshipId
    default:
      return operation.classId
  }
}

/** Descripción corta de la operación, para el historial y los mensajes de la interfaz. */
export function describeOperation(operation: DiagramOperation, classes: UmlClass[]): string {
  const nameOf = (id: string) => classes.find((c) => c.id === id)?.name ?? 'una clase'
  switch (operation.op) {
    case 'ADD_CLASS':
      return `Agregó la clase ${operation.class.name}`
    case 'UPDATE_CLASS':
      return `Modificó ${nameOf(operation.classId)}`
    case 'MOVE_CLASS':
      return `Movió ${nameOf(operation.classId)}`
    case 'REMOVE_CLASS':
      return `Eliminó ${nameOf(operation.classId)}`
    case 'ADD_ATTRIBUTE':
      return `Agregó el atributo ${operation.attribute.name} a ${nameOf(operation.classId)}`
    case 'UPDATE_ATTRIBUTE':
      return `Modificó un atributo de ${nameOf(operation.classId)}`
    case 'REMOVE_ATTRIBUTE':
      return `Eliminó un atributo de ${nameOf(operation.classId)}`
    case 'ADD_METHOD':
      return `Agregó el método ${operation.method.name} a ${nameOf(operation.classId)}`
    case 'UPDATE_METHOD':
      return `Modificó un método de ${nameOf(operation.classId)}`
    case 'REMOVE_METHOD':
      return `Eliminó un método de ${nameOf(operation.classId)}`
    case 'ADD_RELATIONSHIP':
      return `Conectó ${nameOf(operation.relationship.sourceId)} con ${nameOf(operation.relationship.targetId)}`
    case 'UPDATE_RELATIONSHIP':
      return 'Modificó una relación'
    case 'REMOVE_RELATIONSHIP':
      return 'Eliminó una relación'
  }
}
