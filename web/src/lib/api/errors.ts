/** Códigos de error del backend (sección 7.1). `NETWORK_ERROR` y `UNKNOWN` son solo del cliente. */
export type ErrorCode =
  | 'INVALID_CREDENTIALS'
  | 'UNAUTHORIZED'
  | 'USER_INACTIVE'
  | 'COMPANY_DISABLED'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'DUPLICATE_COMPANY'
  | 'DUPLICATE_USER'
  | 'DUPLICATE_PROJECT'
  | 'DUPLICATE_DIAGRAM'
  | 'DUPLICATE_CLASS'
  | 'DUPLICATE_RELATIONSHIP'
  | 'INVALID_RELATIONSHIP'
  | 'INVALID_DATATYPE'
  | 'VERSION_CONFLICT'
  | 'ELEMENT_LOCKED'
  | 'USER_NOT_ELIGIBLE'
  | 'INVALID_STATE_TRANSITION'
  | 'VALIDATION_ERROR'
  | 'OUT_OF_BOUNDS'
  | 'INVALID_JSON'
  | 'DIAGRAM_INCOMPLETE'
  | 'XMI_INVALID'
  | 'GENERATION_FAILED'
  | 'AI_INVALID_RESPONSE'
  | 'AI_UNAVAILABLE'
  | 'AI_TIMEOUT'
  | 'INTERNAL_ERROR'
  | 'NETWORK_ERROR'
  | 'UNKNOWN'

/** Error del backend ya normalizado: mismo formato en REST y en GraphQL. El mensaje viene en español. */
export class ApiError extends Error {
  readonly code: ErrorCode
  readonly status: number | null
  readonly details: Record<string, unknown>

  constructor(code: ErrorCode, message: string, status: number | null = null, details: Record<string, unknown> = {}) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.details = details
  }

  is(...codes: ErrorCode[]): boolean {
    return codes.includes(this.code)
  }
}

export const NETWORK_ERROR_MESSAGE = 'No se pudo conectar con el servidor. Revise su conexión e intente nuevamente.'
const UNKNOWN_ERROR_MESSAGE = 'Ocurrió un error inesperado. Intente nuevamente.'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** Construye un `ApiError` a partir del cuerpo `{code, message, details, timestamp}` de una respuesta HTTP. */
export function apiErrorFromBody(body: unknown, status: number | null): ApiError {
  if (isRecord(body) && typeof body.code === 'string') {
    return new ApiError(
      body.code as ErrorCode,
      typeof body.message === 'string' && body.message ? body.message : UNKNOWN_ERROR_MESSAGE,
      status,
      isRecord(body.details) ? body.details : {},
    )
  }
  return new ApiError('UNKNOWN', UNKNOWN_ERROR_MESSAGE, status)
}

/** En GraphQL el mismo formato viaja en `errors[0]`: el mensaje en `message` y el resto en `extensions`. */
export function apiErrorFromGraphQl(
  error: { message?: string; extensions?: Record<string, unknown> } | undefined,
  status: number | null,
): ApiError {
  const ext = error?.extensions
  if (ext && typeof ext.code === 'string') {
    return new ApiError(
      ext.code as ErrorCode,
      error?.message || UNKNOWN_ERROR_MESSAGE,
      status,
      isRecord(ext.details) ? ext.details : {},
    )
  }
  return new ApiError('UNKNOWN', error?.message || UNKNOWN_ERROR_MESSAGE, status)
}

export function networkError(): ApiError {
  return new ApiError('NETWORK_ERROR', NETWORK_ERROR_MESSAGE)
}

/** Mensaje seguro para mostrar al usuario a partir de cualquier cosa lanzada. */
export function errorMessage(error: unknown): string {
  return error instanceof ApiError ? error.message : UNKNOWN_ERROR_MESSAGE
}
