import { http } from './http'
import type { User } from './types'

export interface Company {
  id: string
  name: string
  slug: string
  active: boolean
  createdAt: string
}

export interface CompanyRequest {
  name: string
  slug: string
}

export interface CompanyAdminRequest {
  username: string
  email: string
  password: string
  fullName: string
}

/** CU-02. Es la única familia de endpoints que no filtra por empresa: solo la usa el `SOFTWARE_ADMIN`. */
export async function listCompanies(): Promise<Company[]> {
  const { data } = await http.get<Company[]>('/companies')
  return data
}

export async function createCompany(body: CompanyRequest): Promise<Company> {
  const { data } = await http.post<Company>('/companies', body)
  return data
}

export async function updateCompany(id: string, body: CompanyRequest): Promise<Company> {
  const { data } = await http.put<Company>(`/companies/${id}`, body)
  return data
}

export async function setCompanyActive(id: string, active: boolean): Promise<Company> {
  const { data } = await http.patch<Company>(`/companies/${id}/status`, { active })
  return data
}

/** Alta del primer `COMPANY_ADMIN` de una empresa. */
export async function createCompanyAdmin(id: string, body: CompanyAdminRequest): Promise<User> {
  const { data } = await http.post<User>(`/companies/${id}/admins`, body)
  return data
}

/** El slug que propone el formulario a partir del nombre: minúsculas, números y guiones. */
export function slugify(name: string): string {
  return name
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 50)
}
