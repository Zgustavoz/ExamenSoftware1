-- 1. TENANT
CREATE TABLE companies (
	id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
	name VARCHAR(100) NOT NULL,
	slug VARCHAR(50) UNIQUE NOT NULL,
	is_active BOOLEAN DEFAULT true,
	created_at TIMESTAMP DEFAULT now()
);

-- 2. USUARIOS
CREATE TABLE users (
	id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
	company_id UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
	username VARCHAR(50) NOT NULL,
	email VARCHAR(100) NOT NULL,
	password_hash VARCHAR(255) NOT NULL,
	full_name VARCHAR(100),
	roles VARCHAR[] DEFAULT '{}',
	fcm_token VARCHAR(500),
	fcm_updated_at TIMESTAMP,
	is_active BOOLEAN DEFAULT true,
	created_at TIMESTAMP DEFAULT now(),
	updated_at TIMESTAMP DEFAULT now(),
	UNIQUE (company_id, username),
	UNIQUE (company_id, email)
);

-- 3. PROYECTOS
CREATE TABLE projects (
	id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
	company_id UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
	name VARCHAR(100) NOT NULL,
	description TEXT,
	owner_id UUID REFERENCES users(id),
	created_at TIMESTAMP DEFAULT now(),
	updated_at TIMESTAMP DEFAULT now()
);

-- 4. DIAGRAMAS
CREATE TABLE diagrams (
	id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
	company_id UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
	project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
	name VARCHAR(100) NOT NULL,
	description TEXT,
	type VARCHAR(50) DEFAULT 'CLASS',
	content_json JSONB NOT NULL,
	version INT DEFAULT 1,
	source_diagram_id UUID REFERENCES diagrams(id) ON DELETE SET NULL,
	created_by UUID REFERENCES users(id),
	created_at TIMESTAMP DEFAULT now(),
	updated_at TIMESTAMP DEFAULT now()
);

-- 5. TAREAS
CREATE TABLE tasks (
	id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
	company_id UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
	diagram_id UUID REFERENCES diagrams(id) ON DELETE CASCADE,
	assigned_to UUID REFERENCES users(id),
	created_by UUID REFERENCES users(id),
	type VARCHAR(50) NOT NULL,
	title VARCHAR(200) NOT NULL,
	description TEXT,
	status VARCHAR(20) DEFAULT 'PENDING',
	result_json JSONB,
	created_at TIMESTAMP DEFAULT now(),
	started_at TIMESTAMP,
	completed_at TIMESTAMP
);

-- 6. CÓDIGO GENERADO
CREATE TABLE generated_code (
	id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
	diagram_id UUID NOT NULL REFERENCES diagrams(id) ON DELETE CASCADE,
	task_id UUID REFERENCES tasks(id) ON DELETE SET NULL,
	user_id UUID REFERENCES users(id),
	language VARCHAR(50) NOT NULL,
	code_content TEXT NOT NULL,
	file_name VARCHAR(255),
	status VARCHAR(20) DEFAULT 'SUCCESS',
	created_at TIMESTAMP DEFAULT now()
);

-- 7. IA
CREATE TABLE ai_chats (
	id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
	diagram_id UUID NOT NULL REFERENCES diagrams(id) ON DELETE CASCADE,
	user_id UUID REFERENCES users(id),
	title VARCHAR(200),
	messages JSONB DEFAULT '[]',   -- [{role, content, timestamp}]
	created_at TIMESTAMP DEFAULT now(),
	updated_at TIMESTAMP DEFAULT now()
);

-- 8. NOTIFICACIONES
CREATE TABLE notifications (
	id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
	company_id UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
	user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	title VARCHAR(200) NOT NULL,
	message TEXT,
	type VARCHAR(50),              -- TASK_ASSIGNED, CODE_READY, ...
	payload_json JSONB,
	is_read BOOLEAN DEFAULT false,
	created_at TIMESTAMP DEFAULT now()
);
