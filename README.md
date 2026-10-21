# Backend IMPULSA A365

## Arranque

Desde esta carpeta:

```powershell
mvn spring-boot:run
```

El puerto habitual es 8089. Si ya hay una instancia antigua, detenerla con Ctrl+C y volver a ejecutar el comando. El frontend se inicia por separado con `npm run dev` dentro de `frontend-a365`.

La conexión a PostgreSQL conserva la configuración existente. El archivo local `.smtp-local.properties` contiene las credenciales SMTP y está excluido de Git; los cambios de SMTP hechos desde la interfaz duran hasta el siguiente reinicio. No subir este archivo ni credenciales a repositorios.

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
