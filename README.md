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
| Dashboard, reportes y aprobación de incidencias | Administrador y supervisor |
| Atención y estados de tickets | Administrador, supervisor y empleado de soporte |
| Tickets y mensajes del cliente | Solo los propios; se omiten las notas internas |

El servidor obtiene autor e ID desde la sesión; ignora identificadores y roles de autor enviados en el cuerpo. EMPLEADO conserva la función actual de agente de soporte hasta separar el rol técnico del solicitante.

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
