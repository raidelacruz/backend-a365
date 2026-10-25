# Trazabilidad funcional — AVPF2, apartado 4.4

Fecha de revisión: 24/10/2026. Referencia: AVPF2-Grupo 1-Proyecto Integrador (1).pdf, páginas PDF 57–60 (actores y matriz RF01–RF14).

## Método de cálculo

Cada RF tiene el mismo peso (1/14). Se cuenta 1 únicamente si sus criterios funcionales están implementados y tienen evidencia verificable; se cuenta 0 si falta una parte del RF. **Los RF parciales no aportan puntos.** Este método conservador es una propuesta para sustentar la entrega, no una ponderación publicada por el docente. No mide documentación, RNF, despliegue ni aceptación de usuarios.

**10 completos / 14 × 100 = 71,43 % funcional implementado.** La aceptación integral en el despliegue actualizado sigue pendiente. No presentar la cifra como 100 % de seguridad, arquitectura o cumplimiento del capítulo VI.

| RF | Criterios comprobables | Estado | Evidencia principal |
|---|---|---|---|
| RF01 | Validar acceso, proteger contraseña, mantener/revocar sesión y restringir recursos privados | Completo | AuthController, SecurityConfig, PasswordService; SecurityIntegrationTest |
| RF02 | Alta, consulta, edición y baja lógica; cinco perfiles: administrador, supervisor, técnico, empleado interno y cliente | Completo | UsuarioController, Access, SchemaUpgrade; Users.jsx, AuthContext; pruebas de permisos |
| RF03 | Registrar cliente corporativo y actualizar razón social, documento y datos de contacto | Completo | ClienteController → ClienteService → ClienteDao; Clients.jsx; ClienteServiceTest y pruebas React |
| RF04 | Buscar clientes por código/documento/razón social/contacto, filtrar estado/fechas; buscar tickets por código, estado, prioridad, fechas o responsable | Completo | ClienteDao con parámetros y filtros; Tickets.jsx; pruebas de filtros y alcance por rol |
| RF05 | Registrar asunto, descripción y categoría; adjuntar y recuperar evidencias con acceso protegido | Completo | TicketController; AdjuntoController → AdjuntoService → AdjuntoDao; NewTicket y TicketAttachments; pruebas multipart, propiedad y validación |
| RF06 | Categorización manual y prioridad Alta/Media/Baja (la matriz admite automática **o** manual) | Completo | Crear ticket y asignación del supervisor; validación de prioridad en backend |
| RF07 | Asignar, reasignar y derivar por supervisor/sistema a técnico calificado | Parcial, no suma | Hay asignación/reasignación manual a técnicos activos y carga pendiente; falta selección automática y especialidad/cualificación |
| RF08 | Gestionar pendiente, atención, resolución y cierre con permisos/transiciones válidas | Completo | ABIERTO/ASIGNADO, EN_PROCESO, PENDIENTE_CLIENTE, RESUELTO, CERRADO/CANCELADO; TicketPolicy y pruebas de espera/reanudación |
| RF09 | Respuestas públicas cronológicas y notas internas del equipo, sin exposición al solicitante | Completo | TicketController mensajes, TicketDetail; pruebas de autor, visibilidad y permisos |
| RF10 | Notificar asignaciones y cambios de estado al solicitante y técnico | Pendiente, no suma | El correo existente es de autenticación/recuperación; no se cuenta como notificación de tickets |
| RF11 | Solicitante consulta avance, responsable y diagnóstico/respuestas de sus propios casos | Completo | Mis solicitudes y detalle/conversación; filtrado del servidor y pruebas de propietario |
| RF12 | Bitácora cronológica inmutable de **todas** las acciones del ticket | Parcial, no suma | Historial de asignación, estados y adjuntos; faltan cobertura completa e inmutabilidad en BD |
| RF13 | Panel con tiempos de atención, cerrados y efectividad por técnico | Parcial, no suma | Hay conteos, categorías y tasa de resolución; faltan tiempos y efectividad por técnico |
| RF14 | Descargar listado y métricas consolidadas en PDF y XLSX reales | Completo | ReporteController → ReporteService → ReporteDao; POI/PDFBox; pruebas de apertura de archivos, contenido, paginación y descarga por período |

RF01 usa sesión de servidor con ID opaco y cookie HttpOnly/Secure bajo HTTPS; BCrypt es hash de contraseña. No se afirma que el ID de sesión esté cifrado ni que se use JWT. Los clientes externos pueden usar correo personal, confirmado mediante el flujo 2FA. Alinear esa redacción con “correo institucional/token encriptado” del informe para describir la implementación real.

## Límites explícitos

- Adjuntos: máximo cinco por ticket, 2 MB cada uno; PDF, PNG y JPEG con firma de contenido. Persisten en PostgreSQL. No se cargan rutas públicas ni se ejecutan los archivos.
- Exportaciones: máximo 2000 tickets por período. Clientes: máximo 500 coincidencias por consulta; se debe afinar la búsqueda si se llega al límite.
- Los reportes exportan los indicadores que existen: registrados, resueltos/cerrados y tasa de resolución; no simulan SLA o productividad.
- CSAT, escalamiento automático y SLA aparecen también en el TO-BE; no se cuentan como RF completos.
- Nuevos módulos con DAO/servicios/controladores; la separación de los controladores antiguos aún es parcial. Testing no equivale a TDD histórico.
- La implementación usa PostgreSQL/Supabase, aunque el documento describe MySQL. Documentar la decisión, no cambiar el nombre del motor en las evidencias.

## Validación y demostración

Resultado del 24/10/2026: **46 pruebas backend y 23 frontend aprobadas**, `mvn verify` correcto con JAR generado y compilación Vite correcta. Backend MVC con JDBC/correo simulados, servicios de clientes/adjuntos y apertura real en memoria de PDF/XLSX. Frontend con API simulada: cinco roles, directorio, filtros, reintento de adjuntos sin duplicar tickets y descarga de reportes. La matriz se refiere al código local; publicar y validar el flujo completo antes de la exposición. La migración V2 no se ejecutó sobre la base real durante estas pruebas.

Demostración: administrador crea empleado interno y técnico; empleado registra ticket con evidencia; supervisor asigna y prioriza; técnico pide información, retoma y resuelve; solicitante confirma cierre; supervisor descarga PDF/Excel del período. Comprobar que un segundo solicitante no puede ver ni descargar archivos ajenos. Luego editar la ficha corporativa y buscarla por documento.

Actualización V2: al arrancar el backend se conserva el rol antiguo de soporte como TECNICO y se crea EMPLEADO solicitante; se agrega contenido BYTEA a ticket_adjuntos. No ejecutar el SQL de inicialización que elimina tablas sobre una base con datos. Revisar el arranque en Render y publicar el frontend inmediatamente después.
