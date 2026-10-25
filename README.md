# Backend IMPULSA A365

## Arranque

Desde esta carpeta:

```powershell
mvn spring-boot:run
```

El puerto habitual es 8089. Si ya hay una instancia antigua, detenerla con Ctrl+C y volver a ejecutar el comando. El frontend se inicia por separado con `npm run dev` dentro de `frontend-a365`.

La conexión local a PostgreSQL se guarda en `.database-local.properties`, excluido de Git y Docker, igual que `.smtp-local.properties`. En Render se usan variables de entorno. Los cambios de SMTP hechos desde la interfaz duran hasta el siguiente reinicio y solo afectan al transporte SMTP. No subir archivos locales ni credenciales a repositorios.

## Seguridad

- BCrypt de coste 12 para altas, cambios y recuperación de contraseña. Mínimo 8 caracteres y máximo 72 bytes para claves nuevas.
- Al arrancar, las claves históricas en texto se convierten a BCrypt dentro de una transacción, manteniendo la contraseña del usuario. Los hashes existentes no se vuelven a cifrar. No se necesita ejecutar SQL manual ni volver contraseñas a texto.
- La sesión vive en el servidor, caduca tras 30 minutos sin actividad y usa cookie A365SESSION con HttpOnly y SameSite=Lax. Se rota el ID al iniciar sesión y al completar la verificación. En despliegues HTTPS configurar SESSION_COOKIE_SECURE=true.
- CSRF está habilitado incluso en el login. GET /api/auth/csrf entrega el token; las escrituras deben enviarlo como X-CSRF-TOKEN junto con la cookie. React ya lo hace.
- GET /api/auth/me devuelve el usuario público de la sesión. POST /api/auth/logout la invalida.
- La verificación y la recuperación guardan únicamente un hash del código en la sesión del servidor, con vencimiento y cinco intentos. No se aceptan códigos sin el paso previo, desde otro navegador ni después de usarlos. No se devuelve codigoDemo ni un token de recuperación en JSON.
- Se limitan intentos de acceso, registro y recuperación en memoria. Si se despliega con varias instancias, el limitador y las sesiones necesitarán almacenamiento compartido.
- Por defecto no se imprimen códigos. Solo para desarrollo local se puede establecer app.security.log-codes=true; permite obtener el código en la consola cuando no hay SMTP. No habilitarlo en producción.
- Los roles y el estado de cuenta se vuelven a consultar en cada petición. Cambiar la contraseña o desactivar una cuenta revoca sus sesiones al siguiente acceso. Un cambio de rol también tiene efecto en la siguiente petición.

## Permisos

| Operación | Acceso |
|---|---|
| Login, alta de cliente y recuperación | Público, con CSRF y límites de intentos |
| Gestión de usuarios, roles, departamentos y SMTP | Administrador |
| Perfil, contraseña, preferencias e historial de accesos | Propietario de la cuenta |
| Consulta de ficha de usuario | Propietario o administrador |
| Dashboard y reportes | Administrador y supervisor |
| Asignación, prioridad, cancelación y reapertura | Solo supervisor |
| Iniciar atención y resolver | Agente TECNICO asignado; debe iniciar antes de resolver |
| Confirmar solución y cerrar | Solicitante del ticket resuelto o supervisor |
| Incidencias | TECNICO registra y consulta las propias; supervisor revisa; administrador consulta |
| Tickets y mensajes del cliente | Solo los propios; se omiten notas internas e historial operativo |
| Historial operativo de tickets | Administrador, supervisor y agente asignado |

El servidor obtiene autor e ID desde la sesión; ignora identificadores y roles de autor enviados en el cuerpo. TECNICO representa al agente de soporte. Cada rol conserva Mis solicitudes para actuar como solicitante de sus propios casos; una persona no puede atender su propio ticket. El administrador tiene consulta de tickets y auditoría, sin sustituir las operaciones del supervisor ni del agente.

Las asignaciones usan la columna existente tickets.id_empleado_asignado y la ficha empleados. La migración incremental V2 se aplica automáticamente al iniciar; no usar el script inicial de limpieza para actualizar una base existente. GET /api/tickets devuelve solo asignados para TECNICO, solo propios para CLIENTE y todos para supervisor/administrador; un filtro idUsuario de un empleado se fuerza a sus propias solicitudes. GET /api/tickets/agentes expone al supervisor únicamente agentes activos, con su carga pendiente. PATCH /api/tickets/{id}/asignacion valida agente, prioridad y motivo; al reasignar vuelve a ASIGNADO. El rol y la activación de los agentes se verifican en la base.

Flujo: ABIERTO → supervisor asigna → ASIGNADO → agente inicia → EN_PROCESO → agente resuelve → RESUELTO → solicitante o supervisor confirma → CERRADO. Solo el supervisor cancela casos activos o reabre casos resueltos/cerrados (la reapertura elimina la asignación para repartirlo otra vez). Los cambios se bloquean por fila dentro de una transacción para evitar sobrescrituras simultáneas. Motivo obligatorio; cada cambio de estado/asignación queda en ticket_historial. Los mensajes aceptan esInterno=true solo del supervisor o agente asignado. Los casos cerrados/cancelados no aceptan mensajes.

Las incidencias solo las crea TECNICO y solo las revisa SUPERVISOR. No se puede revisar una incidencia propia ni modificar una revisión finalizada. Si una cuenta que tenía rol de soporte cambia de rol o se desactiva, el caso queda visible al supervisor como pendiente de un responsable disponible: debe reasignarlo.

El rol EMPLEADO es ahora solicitante interno: solo registra y sigue sus propias solicitudes, igual que CLIENTE pero con identidad interna. TECNICO atiende los casos asignados.

### Guion de demostración

1. Administrador: crear/activar un usuario SUPERVISOR y dos usuarios TECNICO. Las cuentas de ejemplo pueden estar inactivas; revisar Usuarios y roles. Usar correos reales accesibles si se mantiene 2FA.
2. Cliente: registrar una solicitud desde Mis solicitudes.
3. Supervisor: abrir Asignación de tickets, seleccionar el caso y elegir un agente activo, prioridad y motivo. Revisar la carga pendiente del agente.
4. Agente asignado: entrar en Mis casos asignados, iniciar atención, responder y guardar una nota interna. El otro agente no debe ver el caso ni poder abrir su conversación por URL/API.
5. Agente asignado: marcar como resuelto indicando el trabajo realizado.
6. Cliente: comprobar que ve respuestas públicas sin notas internas. En Resueltos, confirmar solución y cerrar.
7. Supervisor: consultar el historial; reabrir y reasignar si hace falta. Revisar una incidencia enviada por el agente.
8. Administrador: consultar historial y usuarios; verificar que no aparecen las acciones de asignación ni resolución del equipo.

Publicar primero el backend en Render y después el frontend en Vercel. Los cambios locales no afectan al despliegue hasta subirlos a sus respectivos repositorios. Las pruebas de esta funcionalidad simulan JDBC/correo y no crean datos ni envían mensajes reales.

Para desarrollo, CORS admite http://localhost:5173 y http://127.0.0.1:5173. Puede ajustarse con app.security.allowed-origins usando una lista de orígenes exactos separados por comas. En producción usar HTTPS y configurar el proxy del frontend para /api.

## Pruebas

```powershell
mvn test
```

Las pruebas de seguridad usan MockMvc, una base simulada y correo simulado: no envían correos ni cambian cuentas reales. Comprueban BCrypt, sesión y CSRF, propietario de recursos, permisos y revocación. El proyecto genera bytecode Java 17; Byte Buddy está fijado a una versión que también permite ejecutar Mockito con el JDK 25 instalado.

Las sesiones y los códigos pendientes se pierden al reiniciar el backend: iniciar el proceso de acceso nuevamente.

## Render Free con Docker y correo HTTPS

1. Subir estos cambios al repositorio del backend (Dockerfile en la raíz, junto a pom.xml).
2. En Render: Language Docker, rama main, Root Directory vacío si pom.xml está en la raíz, Dockerfile Path ./Dockerfile, Docker Command vacío y Compute Free. Docker compila, ejecuta las pruebas y arranca Java 17 como usuario sin privilegios.
3. Añadir las variables de `.env.example` en Environment Variables, reemplazando todos los ejemplos por valores reales. DB_URL es una URL JDBC del Session Pooler de Supabase; usar `sslmode=require`. También se admiten SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME y SPRING_DATASOURCE_PASSWORD como alternativas estándar de Spring.
4. PORT lo proporciona Render; el Dockerfile usa 10000 por defecto. En local, sin PORT, se mantiene 8089.
5. En Advanced, mantener comprobación de salud TCP (no usar una ruta privada de /api como comprobación HTTP).
6. En Vercel hay que configurar un proxy /api hacia este backend antes del fallback de React, para que la cookie de sesión funcione bajo el dominio del frontend. El proxy de desarrollo de Vite no se aplica al build publicado. No usar directamente una URL de Render como VITE_API_URL con la cookie SameSite=Lax actual.

### Correo sin pagar con Brevo

Render Free bloquea los puertos SMTP habituales, incluido Gmail 587. MAIL_PROVIDER=brevo selecciona la API HTTPS; MAIL_PROVIDER=smtp mantiene Gmail en local. No se reintenta por SMTP cuando falla Brevo ni se devuelve un código de acceso al navegador.

1. Crear una cuenta gratuita en https://www.brevo.com/ y completar la activación del correo transaccional que solicite el proveedor.
2. En Settings > Senders, Domains, IPs > Senders, añadir un remitente (por ejemplo, el Gmail propio) y verificarlo con el código recibido. Con direcciones Gmail, Brevo puede reemplazar el dominio visible por @brevosend.com; no se necesita comprar un dominio para esta prueba, pero hay que completar las verificaciones que muestre la cuenta.
3. Crear una clave **API** en la configuración SMTP & API (no la clave SMTP).
4. Guardar solo en Render: MAIL_PROVIDER=brevo, MAIL_FROM=el remitente verificado y BREVO_API_KEY=la clave API. No enviar la clave por chat ni subirla a GitHub. No se necesita la contraseña de aplicación de Gmail en Render.
5. El plan gratuito consultado permite 300 correos al día, compartidos entre códigos, recuperación y alertas. Si se agota la cuota o el proveedor rechaza el envío, el acceso con 2FA no se completa hasta poder enviar un código nuevo. La entrega real queda pendiente de configurar la cuenta y comprobar la bandeja de entrada.

Referencias: https://render.com/docs/docker , https://render.com/docs/free , https://developers.brevo.com/reference/send-transac-email , https://help.brevo.com/hc/es/articles/208580669-FAQ-Cu%C3%A1les-son-los-l%C3%ADmites-del-plan-Gratis

Las instancias Free se suspenden tras inactividad; el siguiente acceso puede tardar y se pierden las sesiones/códigos en memoria. Los datos permanecen en Supabase. Las credenciales que ya estuvieron en commits anteriores deben rotarse; eliminarlas del archivo actual no las elimina del historial.


## Entrega funcional del AVPF2: 71,4 %

La trazabilidad y criterios están en [docs/AVANCE-RF.md](docs/AVANCE-RF.md). Se cuentan únicamente RF completos: 10/14. No se suman los avances parciales ni se afirma que el capítulo VI o todos los RNF estén completos.

### Actualización incremental V2

Al iniciar, SchemaUpgrade aplica dentro de una transacción `db/migration/V2__roles_y_adjuntos.sql`. Conserva IDs y asignaciones: el antiguo rol EMPLEADO de soporte pasa a TECNICO; se añade EMPLEADO como solicitante interno. El script es idempotente y agrega la columna BYTEA contenido a ticket_adjuntos. No borra tablas ni usuarios. Requiere permisos de actualización de roles y ALTER TABLE en Supabase. Los tests MVC no ejecutan esta migración sobre la base real. Publicar backend y frontend consecutivamente y volver a iniciar sesión.

### Directorio corporativo

GET /api/clientes (administrador y supervisor): búsqueda por q (código, razón social, documento, contacto y correo), activo y rango desde/hasta inclusivo. Máximo 500 coincidencias por consulta; afinar filtros si se alcanza el límite. POST /api/clientes y PUT /api/clientes/{id} son exclusivos del administrador. La creación incluye cuenta CLIENTE con contraseña BCrypt; la edición sincroniza correo, documento y teléfono de la cuenta. Razón social, contacto y dirección pertenecen a la ficha corporativa.

### Adjuntos persistentes

GET/POST /api/tickets/{id}/adjuntos y GET /api/tickets/{id}/adjuntos/{idAdjunto}. Se comprueba propiedad/asignación del ticket para listar y descargar. Solo solicitante, técnico asignado o supervisor pueden subir; casos cerrados/cancelados rechazan escrituras. Máximo cinco archivos de 2 MB por ticket, PDF/PNG/JPEG con firma y extensión coincidentes. Se guardan bytes en PostgreSQL, no en el disco temporal de Render. Se descargan como attachment, sin URL pública ni ruta local expuesta. Los archivos históricos sin contenido binario aparecen no disponibles. Conservar dentro de la cuota disponible de la base.

### Espera y reportes

El técnico puede cambiar EN_PROCESO → PENDIENTE_CLIENTE indicando qué información necesita; las respuestas siguen en la conversación. Tras recibirla, retoma EN_PROCESO antes de resolver. Se incluye este estado en pendientes y carga de trabajo.

GET /api/reportes/exportar?formato=pdf|xlsx&desde=YYYY-MM-DD&hasta=YYYY-MM-DD, exclusivo de administrador y supervisor. Máximo 2000 tickets por exportación: si se supera, se pide acotar el período. PDFBox genera PDF paginado y POI genera un XLSX real con hojas Indicadores y Tickets. Incluyen conteos, resolución y detalle filtrado; no incluyen métricas de tiempos/SLA inexistentes. Las celdas usan texto y no ejecutan fórmulas. PDF usa fuentes estándar con sustitución de caracteres que no soporta Windows-1252; XLSX conserva Unicode.

### Librerías nuevas

- Apache POI 5.4.1: generación de Excel, sin abrir archivos subidos por usuarios.
- Apache PDFBox 3.0.8: generación y validación de PDF. Se excluye commons-logging y se conserva el puente spring-jcl.

Pendientes del resto de RF: derivación automática por especialidad (RF07), notificaciones de tickets (RF10), bitácora completa e inmutable (RF12), tiempos/efectividad por técnico (RF13). CSAT y SLA completos siguen pendientes dentro del proceso TO-BE. No se enviaron correos ni se modificaron datos de producción durante las pruebas.
