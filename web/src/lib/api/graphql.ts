import { ClientError, GraphQLClient, type RequestDocument, type Variables } from 'graphql-request'
import { useAuthStore } from '@/stores/auth-store'
import { ApiError, apiErrorFromBody, apiErrorFromGraphQl, networkError } from './errors'

function parseJson(text: unknown): unknown {
  try {
    return typeof text === 'string' ? JSON.parse(text) : null
  } catch {
    return null
  }
}

const client = new GraphQLClient(`${window.location.origin}/graphql`)

/**
 * Ejecuta una query o mutation. Cualquier fallo se lanza como `ApiError` (con `extensions.code` en `code`),
 * de modo que `VERSION_CONFLICT`, `ELEMENT_LOCKED`, `AI_TIMEOUT`… se tratan igual que en REST.
 */
export async function gql<T, V extends Variables = Variables>(document: RequestDocument, variables?: V): Promise<T> {
  const token = useAuthStore.getState().token
  try {
    return await client.request<T>({
      document,
      variables,
      requestHeaders: token ? { Authorization: `Bearer ${token}` } : undefined,
    })
  } catch (error) {
    if (error instanceof ApiError) throw error
    if (error instanceof ClientError) {
      const { response } = error
      const status = response.status ?? null
      // Los errores de resolvers vienen en `errors[]`; los de seguridad (sin token) llegan con el formato REST.
      const first = response.errors?.[0]
      const apiError = first ? apiErrorFromGraphQl(first, status) : apiErrorFromBody(parseJson(response.body), status)
      if (apiError.is('UNAUTHORIZED')) useAuthStore.getState().logout()
      throw apiError
    }
    throw networkError()
  }
}
