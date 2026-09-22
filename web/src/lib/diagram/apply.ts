import type { DiagramOperation } from './operations'
import type { Attribute, ClassContent, Method, Relationship, UmlClass } from './types'

/**
 * La operación que difunde el servidor lleva los ids asignados, pero **no** los valores por omisión: esos
 * los aplica sobre su propia copia al guardar en `content_json`. Se repiten aquí para que el estado local
 * quede igual que el del servidor (mismos criterios que `DiagramOperationApplier`).
 */
function normalizeAttribute(attribute: Partial<Attribute>): Attribute {
  return {
    id: attribute.id ?? '',
    name: attribute.name ?? '',
    type: attribute.type ?? 'String',
    visibility: attribute.visibility ?? 'PRIVATE',
  }
}

function normalizeMethod(method: Partial<Method>): Method {
  return {
    id: method.id ?? '',
    name: method.name ?? '',
    returnType: method.returnType ?? 'void',
    visibility: method.visibility ?? 'PUBLIC',
    parameters: method.parameters ?? [],
  }
}

function normalizeClass(uml: Partial<UmlClass>): UmlClass {
  return {
    id: uml.id ?? '',
    name: uml.name ?? '',
    stereotype: uml.stereotype ?? null,
    visibility: uml.visibility ?? 'PUBLIC',
    x: uml.x ?? 0,
    y: uml.y ?? 0,
    attributes: (uml.attributes ?? []).map(normalizeAttribute),
    methods: (uml.methods ?? []).map(normalizeMethod),
  }
}

/**
 * Aplica al estado local una operación **ya normalizada y validada por el servidor** (la que vuelve en el
 * mensaje `OP`). No valida nada: la autoridad es el backend, aquí solo se refleja el resultado para no tener
 * que recargar el diagrama entero en cada cambio.
 *
 * Devuelve siempre una copia: el estado anterior no se toca.
 */
export function applyOperation(content: ClassContent, operation: DiagramOperation): ClassContent {
  const classes = content.classes
  const relationships = content.relationships

  const withClasses = (next: UmlClass[]): ClassContent => ({ ...content, classes: next })
  const mapClass = (classId: string, change: (cls: UmlClass) => UmlClass): ClassContent =>
    withClasses(classes.map((cls) => (cls.id === classId ? change(cls) : cls)))

  switch (operation.op) {
    case 'ADD_CLASS':
      return withClasses([...classes, normalizeClass(operation.class as Partial<UmlClass>)])

    case 'UPDATE_CLASS':
      return mapClass(operation.classId, (cls) => ({ ...cls, ...operation.changes }))

    case 'MOVE_CLASS':
      return mapClass(operation.classId, (cls) => ({ ...cls, x: operation.x, y: operation.y }))

    case 'REMOVE_CLASS':
      // El servidor borra también las relaciones que tocaban esa clase.
      return {
        ...content,
        classes: classes.filter((cls) => cls.id !== operation.classId),
        relationships: relationships.filter(
          (r) => r.sourceId !== operation.classId && r.targetId !== operation.classId,
        ),
      }

    case 'ADD_ATTRIBUTE':
      return mapClass(operation.classId, (cls) => ({
        ...cls,
        attributes: [...cls.attributes, normalizeAttribute(operation.attribute as Partial<Attribute>)],
      }))

    case 'UPDATE_ATTRIBUTE':
      return mapClass(operation.classId, (cls) => ({
        ...cls,
        attributes: cls.attributes.map((a) =>
          a.id === operation.attributeId ? { ...a, ...operation.changes } : a,
        ),
      }))

    case 'REMOVE_ATTRIBUTE':
      return mapClass(operation.classId, (cls) => ({
        ...cls,
        attributes: cls.attributes.filter((a) => a.id !== operation.attributeId),
      }))

    case 'ADD_METHOD':
      return mapClass(operation.classId, (cls) => ({
        ...cls,
        methods: [...cls.methods, normalizeMethod(operation.method as Partial<Method>)],
      }))

    case 'UPDATE_METHOD':
      return mapClass(operation.classId, (cls) => ({
        ...cls,
        methods: cls.methods.map((m) => (m.id === operation.methodId ? { ...m, ...operation.changes } : m)),
      }))

    case 'REMOVE_METHOD':
      return mapClass(operation.classId, (cls) => ({
        ...cls,
        methods: cls.methods.filter((m) => m.id !== operation.methodId),
      }))

    case 'ADD_RELATIONSHIP':
      return { ...content, relationships: [...relationships, operation.relationship as Relationship] }

    case 'UPDATE_RELATIONSHIP':
      return {
        ...content,
        relationships: relationships.map((r) =>
          r.id === operation.relationshipId ? { ...r, ...operation.changes } : r,
        ),
      }

    case 'REMOVE_RELATIONSHIP':
      return { ...content, relationships: relationships.filter((r) => r.id !== operation.relationshipId) }
  }
}
