import { http } from './http'
import type { LoginRequest, LoginResponse, RegisterCompanyRequest, User } from './types'

/** CU-01. El backend valida la empresa, el usuario y la contraseña, y responde con el JWT. */
export async function login(credentials: LoginRequest): Promise<LoginResponse> {
  const { data } = await http.post<LoginResponse>('/auth/login', credentials)
  return data
}

/** CU-22. Alta pública: crea la empresa y su primer administrador, y devuelve la sesión ya iniciada. */
export async function registerCompany(request: RegisterCompanyRequest): Promise<LoginResponse> {
  const { data } = await http.post<LoginResponse>('/auth/register-company', request)
  return data
}

/** Usuario del token. Sirve para comprobar que la sesión guardada sigue siendo válida. */
export async function fetchMe(): Promise<User> {
  const { data } = await http.get<User>('/auth/me')
  return data
}
