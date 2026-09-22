-- CU-21: además de las tareas automáticas, ahora se crean tareas manuales.
ALTER TABLE tasks DROP CONSTRAINT ck_tasks_type;
ALTER TABLE tasks ADD CONSTRAINT ck_tasks_type
	CHECK (type IN ('CODE_GENERATION', 'XMI_IMPORT', 'XMI_EXPORT', 'MANUAL'));

-- Las consultas de «mis tareas» y «creadas por mí» filtran por estas columnas.
CREATE INDEX idx_tasks_assigned_to ON tasks (assigned_to);
CREATE INDEX idx_tasks_created_by ON tasks (created_by);
