import type { Task } from './codegen'
import { gql } from './graphql'
import { http } from './http'

export type TaskStatus = Task['status']

/** Secuencia admitida por el backend: de cada estado solo se puede avanzar al siguiente. */
export const NEXT_STATUS: Partial<Record<TaskStatus, TaskStatus>> = {
  PENDING: 'IN_PROGRESS',
  IN_PROGRESS: 'COMPLETED',
}

export const STATUS_LABEL: Record<TaskStatus, string> = {
  PENDING: 'Pendiente',
  IN_PROGRESS: 'En curso',
  COMPLETED: 'Completada',
  FAILED: 'Fallida',
}

export const TASK_TYPE_LABEL: Record<string, string> = {
  MANUAL: 'Manual',
  CODE_GENERATION: 'Generación de código',
  XMI_IMPORT: 'Importar XMI',
  XMI_EXPORT: 'Exportar XMI',
}

export interface NewTask {
  diagramId: string
  assignedTo: string
  title: string
  description: string
}

/** Al editar no se cambia el diagrama: la tarea sigue perteneciendo al mismo. */
export interface EditTask {
  assignedTo: string
  title: string
  description: string
}

/** Lo justo para elegir a quién se asigna una tarea; no es la ficha de usuario de CU-03. */
export interface AssignableUser {
  id: string
  username: string
  displayName: string
}

const TASK_FIELDS = `
  id diagramId type title description status resultJson
  assignedTo assignedToName createdBy createdByName
  createdAt startedAt completedAt
`

/** CU-21. El backend avisa a quien se le asigna. */
export async function createTask(input: NewTask): Promise<Task> {
  const data = await gql<{ createTask: Task }>(
    `mutation CreateTask($input: NewTaskInput!) { createTask(input: $input) { ${TASK_FIELDS} } }`,
    { input },
  )
  return data.createTask
}

/** CU-21. Solo avanza al estado siguiente; otro cambio responde `INVALID_STATE_TRANSITION`. */
export async function updateTaskStatus(id: string, status: TaskStatus): Promise<Task> {
  const data = await gql<{ updateTaskStatus: Task }>(
    `mutation UpdateTaskStatus($id: ID!, $status: String!) {
       updateTaskStatus(id: $id, status: $status) { ${TASK_FIELDS} }
     }`,
    { id, status },
  )
  return data.updateTaskStatus
}

/** CU-21. Cambia título, descripción y a quién está asignada. Solo quien la creó o el administrador. */
export async function updateTask(id: string, input: EditTask): Promise<Task> {
  const data = await gql<{ updateTask: Task }>(
    `mutation UpdateTask($id: ID!, $input: EditTaskInput!) {
       updateTask(id: $id, input: $input) { ${TASK_FIELDS} }
     }`,
    { id, input },
  )
  return data.updateTask
}

/** CU-21. Elimina una tarea manual. Devuelve el id para saber cuál quitar de la lista. */
export async function deleteTask(id: string): Promise<string> {
  const data = await gql<{ deleteTask: string }>(`mutation DeleteTask($id: ID!) { deleteTask(id: $id) }`, { id })
  return data.deleteTask
}

/** CU-21. Las asignadas al usuario del token, más las automáticas que lanzó él. */
export async function listMyTasks(status?: TaskStatus): Promise<Task[]> {
  const data = await gql<{ myTasks: Task[] }>(
    `query MyTasks($status: String) { myTasks(status: $status) { ${TASK_FIELDS} } }`,
    { status: status ?? null },
  )
  return data.myTasks
}

/** CU-21. Las tareas manuales que el usuario asignó a otras personas. */
export async function listTasksCreatedByMe(): Promise<Task[]> {
  const data = await gql<{ tasksCreatedByMe: Task[] }>(
    `query TasksCreatedByMe { tasksCreatedByMe { ${TASK_FIELDS} } }`,
  )
  return data.tasksCreatedByMe
}

export async function listAssignableUsers(): Promise<AssignableUser[]> {
  const { data } = await http.get<AssignableUser[]>('/users/assignable')
  return data
}
