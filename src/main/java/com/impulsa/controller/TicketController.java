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
     * 1. OBTENER TICKETS (Filtrado por idUsuario para Vista Cliente, o todos para Empleado/Admin)
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
                       TO_CHAR(t.creado_en, 'YYYY-MM-DD HH24:MI') AS fecha
                FROM tickets t
                LEFT JOIN categorias_ticket c ON t.id_categoria = c.id_categoria
                LEFT JOIN usuarios u ON t.id_usuario_creador = u.id_usuario
                """;

        if (idUsuario != null) {
            String sqlCliente = sqlBase + " WHERE t.id_usuario_creador = ? ORDER BY t.id_ticket DESC";
            return ResponseEntity.ok(jdbcTemplate.queryForList(sqlCliente, idUsuario));
        } else {
            String sqlTodos = sqlBase + " ORDER BY t.id_ticket DESC";
            return ResponseEntity.ok(jdbcTemplate.queryForList(sqlTodos));
        }
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
     * 3. CAMBIAR ESTADO DE UN TICKET (Atención por Empleado o Administrador)
     */
    @PatchMapping("/{idTicket}/estado")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPERVISOR','EMPLEADO')")
    @Transactional
    public ResponseEntity<?> cambiarEstadoTicket(@PathVariable Long idTicket, @RequestBody Map<String, Object> body) {
        try {
            String nuevoEstado = Objects.toString(body.get("estado"), "EN_PROCESO").trim().toUpperCase();
            Integer idUsuario = access.current().id();
            if (!Set.of("ABIERTO", "ASIGNADO", "EN_PROCESO", "RESUELTO", "CERRADO", "CANCELADO").contains(nuevoEstado)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Estado no válido.");
            String motivo = Objects.toString(body.getOrDefault("motivo", "Actualización de atención de soporte"), "");

            String estadoAnterior = jdbcTemplate.queryForObject(
                    "SELECT estado FROM tickets WHERE id_ticket = ?", String.class, idTicket);

            jdbcTemplate.update("""
                    UPDATE tickets
                    SET estado = ?,
                        fecha_atendido = CASE WHEN ? = 'EN_PROCESO' AND fecha_atendido IS NULL THEN CURRENT_TIMESTAMP ELSE fecha_atendido END,
                        fecha_cierre = CASE WHEN ? IN ('RESUELTO', 'CERRADO', 'CANCELADO') THEN CURRENT_TIMESTAMP ELSE fecha_cierre END,
                        actualizado_en = CURRENT_TIMESTAMP
                    WHERE id_ticket = ?
                    """, nuevoEstado, nuevoEstado, nuevoEstado, idTicket);

            jdbcTemplate.update("""
                    INSERT INTO ticket_historial (id_ticket, id_usuario, accion, estado_anterior, estado_nuevo, motivo_cambio)
                    VALUES (?, ?, 'CAMBIO_ESTADO', ?, ?, ?)
                    """, idTicket, idUsuario, estadoAnterior, nuevoEstado, motivo);

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
                       contenido,
                       TO_CHAR(creado_en, 'YYYY-MM-DD HH24:MI') AS fecha
                FROM ticket_mensajes
                WHERE id_ticket = ? AND (es_interno = FALSE OR ? = TRUE)
                ORDER BY id_mensaje ASC
                """;
        return ResponseEntity.ok(jdbcTemplate.queryForList(sql, idTicket, !access.current().role().equals("CLIENTE")));
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

            if (contenido.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "El mensaje no puede estar vacío."));
            }

            jdbcTemplate.update("""
                    INSERT INTO ticket_mensajes (id_ticket, id_usuario, autor_nombre, autor_rol, es_interno, contenido)
                    VALUES (?, ?, ?, ?, FALSE, ?)
                    """, idTicket, idUsuario, autorNombre, autorRol, contenido);

            return ResponseEntity.status(201).body(Map.of("success", true, "mensaje", "Respuesta agregada al ticket."));
        } catch (Exception e) {
            if (e instanceof org.springframework.web.server.ResponseStatusException status) throw status;
            throw new IllegalStateException("No se pudo completar la operación", e);
        }
    }
}
