# Plataforma de Diagramas UML

Plataforma **multi-tenant (por empresa)** para diseñar diagramas de clases UML. Incluye un editor visual con
**colaboración en tiempo real**, un **asistente de IA** que crea y modifica el diagrama desde texto o voz,
**generación de código backend** (Java / Spring Boot), **importación y exportación XMI** (compatible con Enterprise
Architect), **diagramas de secuencia** derivados del de clases, **tareas**, **notificaciones push** y un cliente
móvil que demuestra que el código generado funciona de verdad.

Proyecto del examen de Ingeniería de Software 1 (UAGRM, gestión 2-2026).

## Arquitectura

```
                       ┌─────────────────────────── Servidor (Docker Compose) ───────────────────────────┐
  Navegador (React) ──►│ Nginx :80/:443 ─┬─ SPA de React                                                   │
  App móvil (Flutter)  │                 ├─ /api, /graphql ──► Backend (Spring Boot 3.5 · Java 21)         │
                       │                 └─ /ws (STOMP) ─────►   ├─► PostgreSQL   (datos)                │
                       │                                         ├─► Redis        (presencia y locks)    │
                       │   Solo Nginx se publica.                ├─► RabbitMQ     (difusión STOMP)       │
                       │                                         ├─► ai-service   (FastAPI) ──► LLM      │
                       └─────────────────────────────────────────┴─► FCM (push) · AWS S3 (archivos)  ────┘
```

| Capa | Tecnología |
|---|---|
| Backend | Java 21, Spring Boot 3.5, Spring Security + JWT, JPA/Hibernate + Flyway, Spring GraphQL, WebSocket/STOMP |
| Web | React 19 + TypeScript + Vite, React Router, TanStack Query, Zustand, React Flow, Mermaid, shadcn/ui + Tailwind |
| Móvil | Flutter / Dart, SQLite (`sqflite`), Firebase Cloud Messaging |
| IA | Python 3.11, FastAPI, proveedor LLM intercambiable (OpenAI o compatible, como Gemini; o local) |
| Datos | PostgreSQL 16 (JSONB), Redis 7, RabbitMQ 4 (plugin STOMP) |
| Archivos | AWS S3 (o disco local si no hay bucket) |
| Infra | Docker / Docker Compose, Nginx |

Reparto de protocolos: **REST** para acceso, administración y soporte; **GraphQL** para diagramas, IA, tareas y
código; **WebSocket/STOMP** para la edición colaborativa. El `company_id` se toma **siempre del JWT**.

## Casos de uso

| Módulo | Casos de uso | Estado |
|---|---|---|
| Acceso y administración | CU-01 iniciar sesión · CU-02 empresas · CU-03 usuarios · CU-04/05 proyectos · CU-22 registrar empresa · CU-23 editar y eliminar proyecto | Completos |
| Diseño de diagramas | CU-06 crear · CU-07 editar · CU-08 clases, atributos y métodos · CU-09 relaciones · CU-10 guardar y versionar · CU-11 consultar | Completos |
| IA y código | CU-12 generar con IA · CU-13 historial · CU-14 generar código · CU-15 descargar ZIP | Completos |
| Integración y colaboración | CU-16 XMI · CU-17 tiempo real · CU-19 notificaciones push · CU-20 diagrama de secuencia · CU-21 tareas | Completos |
| Modo offline | CU-18: instrucciones al Copilot (web) y órdenes dictadas (móvil) se guardan sin conexión y se envían al volver | Web y móvil, solo para esas dos acciones |

Roles: `SOFTWARE_ADMIN` (empresas), `COMPANY_ADMIN` (usuarios y proyectos de su empresa), `DESIGNER` (diseña y
genera), `DEVELOPER` (descarga el código generado).

## Estructura del repositorio

```
backend/      Spring Boot: REST + GraphQL + WebSocket            web/        React (Vite + TypeScript)
ai-service/   FastAPI: asistente de IA (servicio interno)        mobile/     Flutter (una pantalla de demostración)
infra/        docker-compose.yml (desarrollo), docker-compose.prod.yml, docker-compose.tls.yml, rabbitmq/, nginx/
.env.example (desarrollo) · .env.prod.example (producción)
```

## Ejecutar en local (desarrollo)

Requisitos: Docker Desktop abierto. Para desarrollar sin contenedores, JDK 21, Python 3.11 y Node 22; Maven no
hace falta (`backend/mvnw`).

```bash
cp .env.example .env        # Windows: copy .env.example .env
# Completa: DB_PASSWORD, JWT_SECRET (≥ 32 caracteres), SEED_ADMIN_PASSWORD, RABBIT_PASSWORD.
# Para el asistente de IA: AI_INTERNAL_KEY, LLM_API_KEY, LLM_MODEL (y LLM_BASE_URL si usas Gemini).
docker compose --env-file .env -f infra/docker-compose.yml up -d --build
```

`--env-file` es necesario porque el `.env` vive en la raíz. La aplicación queda en **http://localhost:8081**.

| Servicio | Dirección | Notas |
|---|---|---|
| Aplicación web | http://localhost:8081 | Nginx: la SPA y el proxy de `/api`, `/graphql` y `/ws` |
| Backend | http://localhost:8080 | REST, GraphQL y WebSocket |
| PostgreSQL | localhost:15432 | puerto desplazado para no chocar con otro PostgreSQL local |
| ai-service | localhost:18000 | solo en 127.0.0.1, para el backend ejecutado en la terminal (el 8000 suele estar ocupado) |

**Camino rápido de desarrollo** (sin reconstruir imágenes): la infraestructura en Docker, el backend en la terminal
y la web con recarga en caliente.

```bash
docker compose --env-file .env -f infra/docker-compose.yml up -d postgres redis rabbitmq ai-service
cd backend && ./mvnw spring-boot:run      # lee el .env de la raíz; DevTools reinicia al recompilar
cd web     && npm ci && npm run dev       # http://localhost:4200, con proxy a :8080
```

**Usuarios de ejemplo.** El administrador de la plataforma es `admin` en la empresa `platform`. En el perfil `dev`
existe además la empresa `demo` con `companyadmin`, `designer` y `developer`. Todos usan la contraseña de
`SEED_ADMIN_PASSWORD`. El formulario de acceso pide **Empresa** (el `slug`), **Usuario** y **Contraseña**.

## Recorrido para ver todo funcionando

Entra como `demo` / `designer` y sigue este orden; cada paso es un caso de uso.

1. **Proyecto** (CU-04): «Nuevo proyecto». **Diagrama** (CU-06): «Nuevo diagrama»; se abre el editor.
2. **Editar** (CU-07 a CU-09): «Agregar clase» dos veces; clic en una clase para ponerle atributos (con lista de tipos) y
   métodos; arrastra de un punto azul de una clase a la otra para relacionarlas. Repetir la misma relación se rechaza
   (CP-01). Mueve una clase y recarga con F5: el cambio persiste (CP-02).
3. **Asistente** (CU-12): pestaña «Asistente», por ejemplo «agrega una clase Cliente con nombre y email». Si el `.env` no
   tiene `LLM_API_KEY` y `LLM_MODEL` responde «no está disponible» y el diagrama no se toca: es el comportamiento correcto.
4. **Secuencia** (CU-20): botón «Secuencia». El actor sin clase se dibuja como muñeco y los objetos solo van arriba.
5. **Código** (CU-14): botón «Código» → «Generar código» (un diagrama con clases vacías se rechaza, CP-06). Como `developer`,
   «Descargar ZIP» (CU-15).
6. **XMI** (CU-16): botón «XMI» para exportar; «Importar XMI» desde la pantalla del proyecto.
7. **Tareas** (CU-21): en el editor, botón «Tarea», o el menú «Tareas»; asígnala a `developer` y mira su notificación.
8. **Colaboración** (CU-17): abre el mismo diagrama en una ventana normal y en otra de incógnito con usuarios distintos:
   lo que uno agrega aparece en el otro sin recargar, y la clase que uno edita se ve bloqueada para el otro.
9. **Sin conexión** (CU-18): en el editor, pestaña «Asistente», F12 → Red → «Sin conexión»; envía una instrucción, vuelve a
   «Sin limitaciones» y se envía sola.
10. **Seguridad entre empresas**: un usuario de otra empresa no ve nada de esta; el recurso ajeno responde `404`.

## Producción en AWS (EC2 + Docker + S3)

Es la arquitectura del enunciado: **una instancia EC2 con Docker Compose**, un **bucket S3** para los archivos y,
opcionalmente, **RDS** para la base de datos. Todo el repositorio está preparado; lo único que tú pones es la
cuenta de AWS y los secretos.

### 1. Lo que se crea en AWS

| Recurso | Configuración recomendada |
|---|---|
| **EC2** | Ubuntu 24.04 · **t3.medium (4 GB)** como mínimo cómodo (el compose limita la memoria a ≈ 2,4 GB) · disco de 20 GB |
| **Grupo de seguridad** | Entrada: **80** y **443** desde cualquier lugar; **22** solo desde tu IP. Nada más: PostgreSQL, Redis y RabbitMQ no se publican |
| **Bucket S3** | Privado (*Block all public access* activado). Guarda los XMI exportados bajo `xmi/<empresa>/<diagrama>/` |
| **Rol de IAM** de la instancia | Solo permiso de escritura en el bucket (abajo). **Sin claves de acceso en ningún archivo** |
| **Elastic IP** + dominio | Para que la dirección no cambie y poder emitir el certificado HTTPS |
| **RDS PostgreSQL** (opcional) | Si no lo usas, PostgreSQL corre en un contenedor con volumen (`postgres-data`) |

Política del rol de IAM (cambia el nombre del bucket):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["s3:PutObject"],
    "Resource": "arn:aws:s3:::NOMBRE-DEL-BUCKET/xmi/*"
  }]
}
```

### 2. En el servidor

```bash
# Docker y Compose (Ubuntu)
curl -fsSL https://get.docker.com | sudo sh && sudo usermod -aG docker $USER   # vuelve a entrar en la sesión

git clone <url-del-repositorio> diagramas && cd diagramas
cp .env.prod.example .env.prod && nano .env.prod       # ver la tabla de variables más abajo
docker compose --env-file .env.prod -f infra/docker-compose.prod.yml up -d --build
docker compose --env-file .env.prod -f infra/docker-compose.prod.yml ps      # todo debe quedar «healthy»
```

Abre `http://<IP-del-servidor>` e inicia sesión con `platform` / `admin` / tu `SEED_ADMIN_PASSWORD`. Después crea una
empresa (CU-02) y su primer administrador.

Qué hace distinto este compose respecto al de desarrollo: solo **Nginx** publica puertos; cada contenedor recibe
**solo las variables que necesita**; el backend arranca con el perfil `prod` (sin usuarios de ejemplo y **se niega a
arrancar** con contraseñas débiles o claves vacías, diciendo todas a la vez); Redis exige contraseña; RabbitMQ no
publica su consola; los registros rotan; y hay límites de memoria y `restart: unless-stopped`.

### 3. HTTPS

- **Opción A, recomendada en AWS: un balanceador (ALB) con certificado de ACM** delante de la instancia. El
  certificado es gratuito y se renueva solo. No hace falta tocar nada de este repositorio: el ALB reenvía al puerto
  80 y admite WebSocket.
- **Opción B, en la propia instancia, con Let's Encrypt:**

```bash
sudo apt install -y certbot && sudo certbot certonly --standalone -d TU-DOMINIO     # el puerto 80 debe estar libre
echo "TLS_DOMAIN=TU-DOMINIO" >> .env.prod
docker compose --env-file .env.prod -f infra/docker-compose.prod.yml -f infra/docker-compose.tls.yml up -d
```

  Nginx redirige el 80 al 443, activa HSTS y admite TLS 1.2 y 1.3. Pon `CORS_ALLOWED_ORIGINS=https://TU-DOMINIO`.

### 4. Variables de `.env.prod`

Todas están explicadas en [`.env.prod.example`](.env.prod.example). Las obligatorias, y cómo generarlas:

| Variable | Qué es | Cómo obtenerla |
|---|---|---|
| `DB_PASSWORD` | contraseña de PostgreSQL | `openssl rand -base64 24` |
| `JWT_SECRET` | firma de las sesiones (≥ 32 caracteres) | `openssl rand -base64 48` |
| `SEED_ADMIN_PASSWORD` | contraseña del administrador de la plataforma (≥ 12) | la eliges tú |
| `REDIS_PASSWORD`, `RABBIT_PASSWORD` | claves de los servicios internos | `openssl rand -base64 24` |
| `AI_INTERNAL_KEY` | clave entre el backend y el ai-service (≥ 16) | `openssl rand -base64 32` |
| `LLM_API_KEY`, `LLM_MODEL`, `LLM_BASE_URL` | proveedor del modelo de lenguaje | tu cuenta (p. ej. Google AI Studio) |
| `CORS_ALLOWED_ORIGINS` | origen público de la aplicación | `https://tu-dominio` |
| `S3_BUCKET`, `AWS_REGION` | dónde se guardan los XMI | el bucket del paso 1 |
| `FCM_CREDENTIALS_JSON` | clave de Firebase **en una sola línea** (base64) | ver abajo |
| `VITE_FIREBASE_*` | configuración pública de Firebase para el push web | consola de Firebase |

**La clave de Firebase en una línea.** No hace falta montar ningún archivo: toda la clave de la cuenta de servicio va en
la variable `FCM_CREDENTIALS_JSON`, en base64 (no tiene comillas ni `\n`, así que sobrevive a Docker, a un `.env` y a
la consola de AWS). El backend acepta también el JSON tal cual en una línea.

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("secrets\firebase-key.json"))   # PowerShell
base64 -w0 secrets/firebase-key.json                                              # Linux / macOS
```

**Base de datos gestionada (RDS).** Deja `COMPOSE_PROFILES=` vacío en `.env.prod` y define
`DB_URL=jdbc:postgresql://<endpoint>:5432/diagramas` (con `DB_USER`/`DB_PASSWORD` de RDS): el contenedor de
PostgreSQL deja de levantarse. Las migraciones de Flyway se aplican solas al arrancar.

### 5. Operación

```bash
# Actualizar a una versión nueva (las migraciones de la base se aplican solas)
git pull && docker compose --env-file .env.prod -f infra/docker-compose.prod.yml up -d --build

# Ver registros / reiniciar un servicio
docker compose --env-file .env.prod -f infra/docker-compose.prod.yml logs -f --tail=100 backend
docker compose --env-file .env.prod -f infra/docker-compose.prod.yml restart backend

# Copia de seguridad de la base (con el PostgreSQL en contenedor)
docker compose --env-file .env.prod -f infra/docker-compose.prod.yml exec -T postgres \
  pg_dump -U diagramas diagramas | gzip > respaldo-$(date +%F).sql.gz
```

Con RDS, las copias las hace RDS (activa los *backups* automáticos). Comprobación de salud: `GET /healthz` (Nginx)
y, dentro de la red, `/actuator/health` (backend).

### 6. Lista de comprobación de seguridad

- Secretos solo en `.env.prod` (fuera de git) y **nunca en las imágenes**; el rol de IAM en vez de claves de AWS.
- Grupo de seguridad con solo 80/443 abiertos; SSH restringido a tu IP.
- Cabeceras de seguridad en Nginx (`X-Frame-Options`, `nosniff`, `Referrer-Policy`, HSTS con HTTPS).
- `company_id` siempre del JWT; recurso de otra empresa → `404`, no `403`. XMI protegido contra XXE; ZIP contra zip-slip.
- El backend revalida toda la salida de la IA. El `ai-service` no se publica y exige la clave interna.
- **Pendiente de tu parte:** rotar `LLM_API_KEY` si alguna vez se compartió, y borrar `secrets/firebase-key.json` del
  equipo local una vez copiada la clave a `FCM_CREDENTIALS_JSON`.

## Pruebas

```bash
cd backend    && ./mvnw test      # unitarias + integración (Testcontainers: necesita Docker)
cd ai-service && pytest           # sin llamadas reales a ningún LLM
cd web        && npm test         # Vitest + Testing Library
cd web        && E2E_PASSWORD=<SEED_ADMIN_PASSWORD> npm run e2e   # Playwright, contra el sistema levantado
cd mobile     && flutter test && flutter analyze                  # y en mobile/test_sqlite: la prueba de SQLite real
```

Las nueve pruebas de aceptación del documento tienen cobertura automatizada:

| CP | Dónde |
|---|---|
| CP-01 relación duplicada | `DiagramOperationApplierTest`, `DesignIT`, `CollabStompIT` |
| CP-02 autoguardado persiste | `DesignIT` |
| CP-03 clase generada por IA | `CopilotIT` |
| CP-04 fallo o timeout de la IA | `CopilotIT`, `AiClientTest` |
| CP-05 / CP-06 generar código; diagrama incompleto | `CodegenIT`, `JavaSpringBootGeneratorTest` (compila lo generado) |
| CP-07 ida y vuelta de XMI | `XmiAdapterTest` |
| CP-08 modo offline | `web/src/lib/offline/queue.test.ts`, `web/e2e/offline.spec.ts`; móvil: `mobile/test/offline/` |
| CP-09 colaboración en vivo | `CollabStompIT` (local) y `DistributedCollabIT` (Redis y RabbitMQ reales) |

Además: aislamiento entre empresas, autorización por rol, `VERSION_CONFLICT`, `ELEMENT_LOCKED`, XXE, zip-slip,
configuración de producción (`ProductionSettingsTest`), almacenamiento en S3 contra un S3 real (`S3StorageIT`, con
MinIO) y credenciales de Firebase en una línea (`FcmCredentialsTest`).

## API en una página

Detalle completo en el código; los códigos de error tienen siempre el formato
`{ "code", "message", "details", "timestamp" }`, con el mensaje en español.

| Área | Endpoints |
|---|---|
| Acceso | `POST /api/auth/login` · `POST /api/auth/register-company` · `GET /api/auth/me` |
| Empresas y usuarios | `/api/companies` (SOFTWARE_ADMIN) · `/api/users` (COMPANY_ADMIN) · `GET /api/users/assignable` |
| Proyectos | `GET/POST /api/projects` · `PUT/DELETE /api/projects/{id}` |
| XMI | `GET /api/diagrams/{id}/xmi` · `POST /api/diagrams/xmi/import` |
| Código generado | `GET /api/generated-code?diagramId=` · `GET /api/generated-code/tasks/{id}/download` (ZIP, `DEVELOPER`) |
| Notificaciones | `GET /api/notifications` · `PATCH /api/notifications/{id}/read` · `PUT /api/me/fcm-token` |
| GraphQL `/graphql` | `diagrams`, `diagram`, `aiChats`, `tasks`, `myTasks`, `tasksCreatedByMe` · `createDiagram`, `saveDiagram` (control optimista con `baseVersion`), `sendAiInstruction`, `confirmAiChanges`, `generateBackendCode`, `generateSequenceDiagram`, `createTask`, `updateTask`, `updateTaskStatus`, `deleteTask` |
| WebSocket `/ws` (STOMP) | `/app/diagram/{id}/join · leave · lock · unlock · op` → `/topic/diagram.{id}` |

Toda edición del diagrama es una **operación** (`ADD_CLASS`, `ADD_ATTRIBUTE`, `ADD_RELATIONSHIP`, `MOVE_CLASS`…) que
pasa por un único validador en el servidor, ya venga del editor, del WebSocket o de la IA.

## Móvil

`mobile/` es una app Flutter de **una sola pantalla** que demuestra el backend generado: se dicta «registra un cliente con
nombre Juan» y el registro aparece en la API que generó la plataforma. 

1. Genera el código de un diagrama (web → **Código → Generar código**), descarga el ZIP como `developer`, descomprímelo
   y ejecuta `mvn spring-boot:run` (o `backend/mvnw -f <carpeta>/pom.xml spring-boot:run`): queda en `:8090` con H2.
2. `cd mobile && flutter pub get && flutter run --dart-define=API_URL=http://10.0.2.2:8080` (`10.0.2.2` es el `localhost`
   del PC visto desde el emulador; en un móvil físico, la IP del PC).
3. **Sin conexión**, la orden se guarda en SQLite y se envía sola al volver la red, en orden. **Notificaciones**: cada
   quien usa su propio proyecto de Firebase (`flutterfire configure`); sin `google-services.json` la app funciona sin push.

El proyecto generado trae entidades JPA, repositorios y controladores CRUD por clase concreta, más un `pom.xml`. No trae
autenticación, DTOs ni lógica de negocio: es un punto de partida.

## Decisiones de diseño (resumen)

Los comentarios del código citan decisiones numeradas (D-04, D-07, D-36…). El detalle completo estaba en
`docs/DECISIONS.md`, que ya no está en el árbol pero sigue en el historial de git
(`git log --diff-filter=D --oneline -- docs/DECISIONS.md`, y luego `git show <commit>^:docs/DECISIONS.md`).

- **Multi-tenant por JWT**, con login por `empresa/usuario/contraseña` (el `username` solo es único por empresa).
- **Un solo validador de operaciones** para el editor, la colaboración y la IA; el backend nunca confía en lo que
  devuelve el modelo de lenguaje.
- **La IA aplica y persiste al responder** (los cambios son idempotentes al confirmar); si falla, el diagrama no se toca.
- **Solo se genera Java/Spring Boot**; el XMI usa JAXP/DOM (sin EMF) y conserva las posiciones del lienzo.
- **Colaboración distribuida**: Redis para presencia y locks por elemento, RabbitMQ como *broker relay* STOMP.
- **Offline por reglas simples**: guardar si no hay red, enviar en orden y de una en una; solo un fallo de red reintenta,
  un rechazo del servidor no. Entrega «al menos una vez».
- **Sin secretos en el repositorio**; la credencial de Firebase viaja en una variable de entorno de una línea.
- **El generador no serializa el extremo inverso de las relaciones**, para evitar ciclos de JSON en la API generada.

## Limitaciones conocidas

- Editar el diagrama a mano **sin conexión** no está soportado (solo las instrucciones al asistente y las órdenes del móvil).
- La entrega offline es «al menos una vez»: si la respuesta se pierde tras llegar la petición, podría repetirse.
- El proyecto generado no incluye seguridad ni DTOs; los métodos salen con un `TODO`.
- La app móvil no se ha compilado en un equipo con poca memoria (Gradle pide 8 GB); en la de quien la desarrolla sí.
- No hay recuperación de contraseña, verificación de correo ni restauración de versiones anteriores de un diagrama.
