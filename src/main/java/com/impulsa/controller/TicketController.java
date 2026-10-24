package com.impulsa.controller;

import com.impulsa.security.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired private Access access;
    @Autowired private AccountService accounts;

    /**
     * 1. Listado según rol: propios, asignados o consulta global.
     */
    @GetMapping
    public ResponseEntity<?> listarTickets(@RequestParam(required = false) Integer idUsuario) {
        if (access.current().role().equals("CLIENTE")) idUsuario = access.current().id();
        String sqlBase = """
                SELECT t.id_ticket AS "idTicket",
                       t.codigo_ticket AS codigo,
                       t.asunto,
                       t.descripcion,
                       COALESCE(c.nombre, 'Software') AS categoria,
                       t.prioridad,
                       t.estado,
                       COALESCE(t.solicitante_nombre, u.nombres || ' ' || COALESCE(u.apellidos, '')) AS solicitante,
                       t.id_usuario_creador AS "idUsuarioCreador",
                       t.id_empleado_asignado AS "idEmpleadoAsignado",
                       agente.id_usuario AS "idUsuarioAsignado",
                       CASE WHEN r.nombre = 'EMPLEADO' AND agente.activo THEN agente.nombres || ' ' || COALESCE(agente.apellidos, '') ELSE NULL END AS responsable,
                       TO_CHAR(t.creado_en, 'YYYY-MM-DD HH24:MI') AS fecha
                FROM tickets t
                LEFT JOIN categorias_ticket c ON t.id_categoria = c.id_categoria
                LEFT JOIN usuarios u ON t.id_usuario_creador = u.id_usuario
                LEFT JOIN empleados e ON e.id_empleado = t.id_empleado_asignado
                LEFT JOIN usuarios agente ON agente.id_usuario = e.id_usuario
                LEFT JOIN roles r ON r.id_rol = agente.id_rol
                """;

        if (access.current().role().equals("EMPLEADO") && idUsuario == null) {
            return ResponseEntity.ok(jdbcTemplate.queryForList(sqlBase + " WHERE agente.id_usuario = ? AND t.id_usuario_creador <> ? ORDER BY t.id_ticket DESC", access.current().id(), access.current().id()));
        }
        if (idUsuario != null) {
            if (access.current().role().equals("EMPLEADO")) idUsuario = access.current().id();
            String sqlCliente = sqlBase + " WHERE t.id_usuario_creador = ? ORDER BY t.id_ticket DESC";
            return ResponseEntity.ok(jdbcTemplate.queryForList(sqlCliente, idUsuario));
        } else {
            String sqlTodos = sqlBase + " ORDER BY t.id_ticket DESC";
            return ResponseEntity.ok(jdbcTemplate.queryForList(sqlTodos));
        }
    }

    @GetMapping("/agentes")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ResponseEntity<?> agentes() {
        return ResponseEntity.ok(jdbcTemplate.queryForList("""
                SELECT e.id_empleado AS "idEmpleado", u.id_usuario AS "idUsuario",
                       u.nombres || ' ' || COALESCE(u.apellidos, '') AS nombre,
                       u.departamento, COUNT(t.id_ticket)::INT AS pendientes
                FROM empleados e JOIN usuarios u ON u.id_usuario = e.id_usuario
                JOIN roles r ON r.id_rol = u.id_rol
                LEFT JOIN tickets t ON t.id_empleado_asignado = e.id_empleado AND t.estado IN ('ASIGNADO','EN_PROCESO')
                WHERE r.nombre = 'EMPLEADO' AND u.activo = TRUE
                GROUP BY e.id_empleado, u.id_usuario ORDER BY nombre
                """));
    }

    @PatchMapping("/{idTicket}/asignacion")
    @PreAuthorize("hasRole('SUPERVISOR')")
    @Transactional
    public ResponseEntity<?> asignar(@PathVariable Long idTicket, @RequestBody Map<String, Object> body) {
        Map<String, Object> ticket = lockedTicket(idTicket);
        String previous = ticket.get("estado").toString();
        if (TicketPolicy.TERMINAL.contains(previous)) throw status(409, "Reabre el ticket antes de asignarlo.");
        int employeeId;
        try { employeeId = Integer.parseInt(Objects.toString(body.get("idEmpleado"), "")); }
        catch (NumberFormatException e) { throw status(400, "Selecciona un agente válido."); }
        String reason = reason(body);
        String priority = Objects.toString(body.getOrDefault("prioridad", ticket.get("prioridad")), "");
        if (!Set.of("Baja", "Media", "Alta").contains(priority)) throw status(400, "Prioridad no válida.");
        var candidates = jdbcTemplate.queryForList("""
                SELECT e.id_usuario FROM empleados e JOIN usuarios u ON u.id_usuario = e.id_usuario
                JOIN roles r ON r.id_rol = u.id_rol
                WHERE e.id_empleado = ? AND u.activo = TRUE AND r.nombre = 'EMPLEADO' FOR SHARE OF u, e
                """, employeeId);
        if (candidates.isEmpty()) throw status(400, "El agente debe estar activo y tener rol EMPLEADO.");
        if (((Number)candidates.get(0).get("id_usuario")).intValue() == ((Number)ticket.get("id_usuario_creador")).intValue())
            throw status(400, "El solicitante no puede atender su propio caso.");
        Object oldEmployee = ticket.get("id_empleado_asignado");
        boolean same = oldEmployee instanceof Number number && number.intValue() == employeeId;
        String next = same && !previous.equals("ABIERTO") ? previous : "ASIGNADO";
        jdbcTemplate.update("""
                UPDATE tickets SET id_empleado_asignado = ?, prioridad = ?, estado = ?,
                    fecha_atendido = CASE WHEN ? THEN fecha_atendido ELSE NULL END, actualizado_en = CURRENT_TIMESTAMP
                WHERE id_ticket = ?
                """, employeeId, priority, next, same, idTicket);
        String detail = "Agente " + Objects.toString(oldEmployee, "sin asignar") + " → " + employeeId
                + "; prioridad " + ticket.get("prioridad") + " → " + priority + ". " + reason;
        history(idTicket, same ? "CLASIFICACION" : "ASIGNACION", previous, next, detail);
        return ResponseEntity.ok(Map.of("success", true, "estado", next, "mensaje", "Asignación y prioridad guardadas."));
    }

    private Map<String, Object> lockedTicket(Long id) {
        var rows = jdbcTemplate.queryForList("SELECT * FROM tickets WHERE id_ticket = ? FOR UPDATE", id);
        if (rows.isEmpty()) throw status(404, "Ticket no encontrado.");
        return rows.get(0);
    }

    private org.springframework.web.server.ResponseStatusException status(int code, String message) {
        return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatusCode.valueOf(code), message);
    }

    private String reason(Map<String, Object> body) {
        String reason = Objects.toString(body.get("motivo"), "").trim();
        if (reason.isEmpty() || reason.length() > 180) throw status(400, "Indica un motivo de hasta 180 caracteres.");
        return reason;
    }

    private void history(Long id, String action, String previous, String next, String reason) {
        jdbcTemplate.update("INSERT INTO ticket_historial (id_ticket, id_usuario, accion, estado_anterior, estado_nuevo, motivo_cambio) VALUES (?, ?, ?, ?, ?, ?)",
                id, access.current().id(), action, previous, next, reason.length() > 255 ? reason.substring(0, 255) : reason);
    }

    /**
     * 2. CREAR NUEVO TICKET (Desde Vista Cliente o desde Mesa de Ayuda Admin/Empleado)
     */
    @PostMapping
    @Transactional
    public ResponseEntity<?> crearTicket(@RequestBody Map<String, Object> body) {
        try {
            String asunto = Objects.toString(body.get("asunto"), "").trim();
            String descripcion = Objects.toString(body.get("descripcion"), "").trim();
            String categoriaInput = Objects.toString(body.getOrDefault("categoria", "Software"), "Software").trim();
            String prioridad = Objects.toString(body.getOrDefault("prioridad", "Media"), "Media").trim();
            Integer idUsuarioCreador = access.current().id();
            String solicitanteNombre = accounts.name(accounts.require(idUsuarioCreador));

            if (asunto.isEmpty() || descripcion.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "El asunto y la descripción son obligatorios."));
            }

            // Resolver categoría
            Integer idCat = 1;
            if (categoriaInput.matches("\\d+")) {
                idCat = Integer.parseInt(categoriaInput);
            } else {
                List<Map<String, Object>> cats = jdbcTemplate.queryForList(
                        "SELECT id_categoria FROM categorias_ticket WHERE LOWER(nombre) LIKE LOWER(?) LIMIT 1",
                        "%" + categoriaInput + "%");
                if (!cats.isEmpty()) {
                    idCat = (Integer) cats.get(0).get("id_categoria");
                }
            }

            if (solicitanteNombre.isEmpty()) {
                List<Map<String, Object>> uRows = jdbcTemplate.queryForList(
                        "SELECT nombres, apellidos FROM usuarios WHERE id_usuario = ?", idUsuarioCreador);
                if (!uRows.isEmpty()) {
                    solicitanteNombre = (Objects.toString(uRows.get(0).get("nombres"), "") + " " +
                            Objects.toString(uRows.get(0).get("apellidos"), "")).trim();
                } else {
                    solicitanteNombre = "Cliente Corporativo";
                }
            }

            Integer maxId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id_ticket), 0)::INT FROM tickets", Integer.class);
            String codigoTicket = String.format("TK-2026-%03d", (maxId != null ? maxId + 1 : 4));

            String sqlInsert = """
                    INSERT INTO tickets (codigo_ticket, asunto, descripcion, id_categoria, prioridad, estado, id_usuario_creador, solicitante_nombre, fecha_limite)
                    VALUES (?, ?, ?, ?, ?, 'ABIERTO', ?, ?, CURRENT_TIMESTAMP + INTERVAL '12 hours')
                    RETURNING id_ticket
                    """;

            Long idTicket = jdbcTemplate.queryForObject(sqlInsert, Long.class,
                    codigoTicket, asunto, descripcion, idCat, prioridad, idUsuarioCreador, solicitanteNombre);

            // Guardar el mensaje inicial en ticket_mensajes
            jdbcTemplate.update("""
                    INSERT INTO ticket_mensajes (id_ticket, id_usuario, autor_nombre, autor_rol, es_interno, contenido)
                    VALUES (?, ?, ?, 'Solicitante', FALSE, ?)
                    """, idTicket, idUsuarioCreador, solicitanteNombre, descripcion);

            return ResponseEntity.status(201).body(Map.of(
                    "success", true,
                    "codigoTicket", codigoTicket,
                    "idTicket", idTicket,
                    "mensaje", "Ticket registrado exitosamente con código #" + codigoTicket
            ));
        } catch (Exception e) {
            if (e instanceof org.springframework.web.server.ResponseStatusException status) throw status;
            throw new IllegalStateException("No se pudo completar la operación", e);
        }
    }

    /**
     * 3. Transiciones por rol: atención, supervisión o confirmación del solicitante.
     */
    @PatchMapping("/{idTicket}/estado")
    @PreAuthorize("@access.canReadTicket(#idTicket, authentication)")
    @Transactional
    public ResponseEntity<?> cambiarEstadoTicket(@PathVariable Long idTicket, @RequestBody Map<String, Object> body) {
        try {
            String nuevoEstado = Objects.toString(body.get("estado"), "EN_PROCESO").trim().toUpperCase();
            if (!Set.of("ABIERTO", "ASIGNADO", "EN_PROCESO", "RESUELTO", "CERRADO", "CANCELADO").contains(nuevoEstado)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Estado no válido.");
            String role = access.current().role();
            if (!Set.of("SUPERVISOR", "EMPLEADO").contains(role) && !nuevoEstado.equals("CERRADO")) throw status(403, "Tu rol no puede ejecutar esta acción.");
            var ticket = lockedTicket(idTicket);
            boolean assigned = access.canAttendTicket(idTicket, access.authentication());
            boolean owner = ((Number)ticket.get("id_usuario_creador")).intValue() == access.current().id();
            if (!role.equals("SUPERVISOR") && !assigned && !owner) throw status(403, "Solo el solicitante puede confirmar la solución.");
            String motivo = reason(body);
            String estadoAnterior = ticket.get("estado").toString();
            TicketPolicy.transition(role, assigned, owner, estadoAnterior, nuevoEstado);

            jdbcTemplate.update("""
                    UPDATE tickets
                    SET estado = ?,
                        fecha_atendido = CASE WHEN ? = 'EN_PROCESO' AND fecha_atendido IS NULL THEN CURRENT_TIMESTAMP ELSE fecha_atendido END,
                        fecha_cierre = CASE WHEN ? IN ('RESUELTO', 'CERRADO', 'CANCELADO') THEN CURRENT_TIMESTAMP ELSE NULL END,
                        id_empleado_asignado = CASE WHEN ? = 'ABIERTO' THEN NULL ELSE id_empleado_asignado END,
                        actualizado_en = CURRENT_TIMESTAMP
                    WHERE id_ticket = ?
                    """, nuevoEstado, nuevoEstado, nuevoEstado, nuevoEstado, idTicket);
            history(idTicket, "CAMBIO_ESTADO", estadoAnterior, nuevoEstado, motivo);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "estado", nuevoEstado,
                    "mensaje", "Estado del ticket actualizado a " + nuevoEstado
            ));
        } catch (Exception e) {
            if (e instanceof org.springframework.web.server.ResponseStatusException status) throw status;
            throw new IllegalStateException("No se pudo completar la operación", e);
        }
    }

    /**
     * 4. OBTENER MENSAJES / CHAT TÉCNICO DE UN TICKET
     */
    @GetMapping("/{idTicket}/mensajes")
    @PreAuthorize("@access.canReadTicket(#idTicket, authentication)")
    public ResponseEntity<?> listarMensajes(@PathVariable Long idTicket) {
        String sql = """
                SELECT id_mensaje AS "idMensaje",
                       autor_nombre AS "autorNombre",
                       autor_rol AS "autorRol",
                       contenido, es_interno AS "esInterno",
                       TO_CHAR(creado_en, 'YYYY-MM-DD HH24:MI') AS fecha
                FROM ticket_mensajes
                WHERE id_ticket = ? AND (es_interno = FALSE OR ? = TRUE)
                ORDER BY id_mensaje ASC
                """;
        return ResponseEntity.ok(jdbcTemplate.queryForList(sql, idTicket, access.canReadInternal(idTicket, access.authentication())));
    }

    @GetMapping("/{idTicket}/historial")
    @PreAuthorize("@access.canReadInternal(#idTicket, authentication)")
    public ResponseEntity<?> historial(@PathVariable Long idTicket) {
        return ResponseEntity.ok(jdbcTemplate.queryForList("""
                SELECT h.id_historial AS "idHistorial", h.accion, h.estado_anterior AS "estadoAnterior",
                    h.estado_nuevo AS "estadoNuevo", h.motivo_cambio AS motivo,
                    COALESCE(u.nombres || ' ' || COALESCE(u.apellidos, ''), 'Sistema') AS autor,
                    TO_CHAR(h.creado_en, 'YYYY-MM-DD HH24:MI') AS fecha
                FROM ticket_historial h LEFT JOIN usuarios u ON u.id_usuario = h.id_usuario
                WHERE h.id_ticket = ? ORDER BY h.id_historial DESC
                """, idTicket));
    }

    /**
     * 5. AGREGAR RESPUESTA AL HILO DEL TICKET (Empleado, Admin o Cliente)
     */
    @PostMapping("/{idTicket}/mensajes")
    @PreAuthorize("@access.canReadTicket(#idTicket, authentication)")
    @Transactional
    public ResponseEntity<?> agregarMensaje(@PathVariable Long idTicket, @RequestBody Map<String, Object> body) {
        try {
            String contenido = Objects.toString(body.get("contenido"), "").trim();
            Integer idUsuario = access.current().id();
            String autorNombre = accounts.name(accounts.require(idUsuario));
            String autorRol = access.current().role();
            var ticket = lockedTicket(idTicket);
            if (Set.of("CERRADO", "CANCELADO").contains(ticket.get("estado"))) throw status(409, "Este caso está cerrado. Solicita su reapertura al supervisor.");
            boolean owner = ((Number)ticket.get("id_usuario_creador")).intValue() == idUsuario;
            if (!(owner || autorRol.equals("SUPERVISOR") || access.canAttendTicket(idTicket, access.authentication())))
                throw status(403, "Tu acceso a este caso es de consulta.");
            boolean internal = Boolean.TRUE.equals(body.get("esInterno"));
            if (internal && !(autorRol.equals("SUPERVISOR") || access.canAttendTicket(idTicket, access.authentication())))
                throw status(403, "Solo el equipo de atención puede publicar notas internas.");

            if (contenido.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "El mensaje no puede estar vacío."));
            }

            jdbcTemplate.update("""
                    INSERT INTO ticket_mensajes (id_ticket, id_usuario, autor_nombre, autor_rol, es_interno, contenido)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, idTicket, idUsuario, autorNombre, autorRol, internal, contenido);

            return ResponseEntity.status(201).body(Map.of("success", true, "mensaje", "Respuesta agregada al ticket."));
        } catch (Exception e) {
            if (e instanceof org.springframework.web.server.ResponseStatusException status) throw status;
            throw new IllegalStateException("No se pudo completar la operación", e);
        }
    }
}
