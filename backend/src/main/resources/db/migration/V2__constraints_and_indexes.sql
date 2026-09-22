-- Restricciones de dominio (D-08): el DDL original no las trae, el diseño lógico y los CU las exigen.
ALTER TABLE tasks ADD CONSTRAINT ck_tasks_type
	CHECK (type IN ('CODE_GENERATION', 'XMI_IMPORT', 'XMI_EXPORT'));
ALTER TABLE tasks ADD CONSTRAINT ck_tasks_status
	CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED'));
ALTER TABLE generated_code ADD CONSTRAINT ck_generated_code_status
	CHECK (status IN ('SUCCESS', 'FAILED'));
ALTER TABLE diagrams ADD CONSTRAINT ck_diagrams_type
	CHECK (type IN ('CLASS', 'SEQUENCE'));

-- Unicidad de nombres (CU-02, CU-04, CU-06)
CREATE UNIQUE INDEX uq_companies_name ON companies (lower(name));
CREATE UNIQUE INDEX uq_projects_company_name ON projects (company_id, lower(name));
CREATE UNIQUE INDEX uq_diagrams_project_name ON diagrams (project_id, lower(name));

-- Índices
CREATE INDEX idx_diagrams_content_json ON diagrams USING GIN (content_json);
CREATE INDEX idx_users_company ON users (company_id);
CREATE INDEX idx_projects_company ON projects (company_id);
CREATE INDEX idx_diagrams_company ON diagrams (company_id);
CREATE INDEX idx_diagrams_project ON diagrams (project_id);
CREATE INDEX idx_tasks_company ON tasks (company_id);
CREATE INDEX idx_notifications_company ON notifications (company_id);
CREATE INDEX idx_notifications_user_read ON notifications (user_id, is_read);
CREATE INDEX idx_generated_code_diagram ON generated_code (diagram_id);
CREATE INDEX idx_generated_code_task ON generated_code (task_id);
CREATE INDEX idx_ai_chats_diagram ON ai_chats (diagram_id);
