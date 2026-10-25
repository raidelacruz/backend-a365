-- Actualización incremental: conserva cuentas, asignaciones y documentos existentes.
-- EMPLEADO representaba soporte en la versión anterior: se conserva su ID como TECNICO.
UPDATE roles SET nombre = 'TECNICO', descripcion = 'Atención de casos asignados y diagnóstico técnico'
WHERE nombre = 'EMPLEADO' AND NOT EXISTS (SELECT 1 FROM roles WHERE nombre = 'TECNICO');
INSERT INTO roles (nombre, descripcion, nivel_acceso)
VALUES ('TECNICO', 'Atención de casos asignados y diagnóstico técnico', 2),
       ('EMPLEADO', 'Solicitante interno: registra y sigue sus propias solicitudes', 1)
ON CONFLICT (nombre) DO NOTHING;
ALTER TABLE ticket_adjuntos ADD COLUMN IF NOT EXISTS contenido BYTEA;
