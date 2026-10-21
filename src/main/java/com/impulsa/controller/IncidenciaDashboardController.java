package com.impulsa.controller;

import com.impulsa.service.EmailNotificationService;
import com.impulsa.security.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPERVISOR','EMPLEADO')")
public class IncidenciaDashboardController {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired private Access access;
    @Autowired private AccountService accounts;

    @Autowired
    private EmailNotificationService emailService;

    /**
     * 1. RESUMEN DE KPIS Y PORCENTAJES POR CATEGORÍA EN TIEMPO REAL (Para dashboard.html y reportes.html)
     */
    @GetMapping("/dashboard/resumen")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPERVISOR')")
    public ResponseEntity<?> obtenerResumenDashboard() {
        Integer pendientes = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tickets WHERE estado IN ('ABIERTO', 'EN_PROCESO', 'ASIGNADO')", Integer.class);
        Integer atendidos = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tickets WHERE estado IN ('RESUELTO', 'CERRADO')", Integer.class);
        Integer cancelados = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tickets WHERE estado = 'CANCELADO'", Integer.class);
        Integer totalTickets = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tickets", Integer.class);
        Integer totalUsuarios = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM usuarios", Integer.class);
        Integer totalIncidencias = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM incidencias", Integer.class);

        List<Map<String, Object>> ticketsRecientes = jdbcTemplate.queryForList("""
                SELECT id_ticket AS "idTicket", codigo_ticket AS codigo, asunto, prioridad, estado,
                       solicitante_nombre AS solicitante, TO_CHAR(creado_en, 'YYYY-MM-DD HH24:MI') AS fecha
                FROM tickets
                ORDER BY id_ticket DESC LIMIT 5
                """);

        List<Map<String, Object>> porCategoria = jdbcTemplate.queryForList("""
                SELECT c.nombre AS categoria, COUNT(t.id_ticket)::INT AS total
                FROM categorias_ticket c
                LEFT JOIN tickets t ON c.id_categoria = t.id_categoria
                GROUP BY c.id_categoria, c.nombre
                ORDER BY c.id_categoria ASC
                """);

        return ResponseEntity.ok(Map.of(
                "pendientes", pendientes != null ? pendientes : 0,
                "atendidos", atendidos != null ? atendidos : 0,
                "cancelados", cancelados != null ? cancelados : 0,
                "totalMes", totalTickets != null ? totalTickets : 0,
                "totalUsuarios", totalUsuarios != null ? totalUsuarios : 0,
                "totalIncidencias", totalIncidencias != null ? totalIncidencias : 0,
                "ticketsRecientes", ticketsRecientes,
                "porCategoria", porCategoria
        ));
    }

    /**
     * 2. LISTAR INCIDENCIAS (Módulo incidencias.html)
     */
    @GetMapping("/incidencias")
    public ResponseEntity<?> listarIncidencias() {
        String sql = """
                SELECT id_incidencia AS "idIncidencia",
                       codigo_incidencia AS codigo,
                       tipo,
                       titulo,
                       detalle,
                       estado,
                       registrado_por AS "registradoPor",
                       TO_CHAR(creado_en, 'YYYY-MM-DD') AS fecha
                FROM incidencias
                ORDER BY id_incidencia DESC
                """;
        return ResponseEntity.ok(jdbcTemplate.queryForList(sql));
    }

    /**
     * 3. REGISTRAR NUEVA INCIDENCIA
     */
    @PostMapping("/incidencias")
    public ResponseEntity<?> crearIncidencia(@RequestBody Map<String, Object> body) {
        try {
            String tipo = Objects.toString(body.getOrDefault("tipo", "Actualización"), "").trim();
            String titulo = Objects.toString(body.get("titulo"), "").trim();
            String detalle = Objects.toString(body.get("detalle"), "").trim();
            Integer idUsuario = access.current().id();
            String registradoPor = accounts.name(accounts.require(idUsuario));

            String codigo = "INC-" + (1000 + new Random().nextInt(9000));
            jdbcTemplate.update("""
                    INSERT INTO incidencias (codigo_incidencia, tipo, titulo, detalle, estado, id_usuario_registro, registrado_por)
                    VALUES (?, ?, ?, ?, 'Pendiente', ?, ?)
                    """, codigo, tipo, titulo, detalle, idUsuario, registradoPor);

            return ResponseEntity.status(201).body(Map.of(
                    "success", true,
                    "codigo", codigo,
                    "mensaje", "Incidencia registrada con código " + codigo
            ));
        } catch (Exception e) {
            if (e instanceof org.springframework.web.server.ResponseStatusException status) throw status;
            throw new IllegalStateException("No se pudo completar la operación", e);
        }
    }

    /**
     * 4. APROBAR / RECHAZAR INCIDENCIA (Supervisor / Administrador)
     */
    @PatchMapping("/incidencias/{codigo}/estado")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPERVISOR')")
    public ResponseEntity<?> cambiarEstadoIncidencia(@PathVariable String codigo, @RequestBody Map<String, Object> body) {
        try {
            String estado = Objects.toString(body.get("estado"), "Aprobada").trim();
            jdbcTemplate.update("UPDATE incidencias SET estado = ?, actualizado_en = CURRENT_TIMESTAMP WHERE codigo_incidencia = ?", estado, codigo);
            return ResponseEntity.ok(Map.of("success", true, "estado", estado, "mensaje", "Incidencia " + codigo + " marcada como " + estado));
        } catch (Exception e) {
            if (e instanceof org.springframework.web.server.ResponseStatusException status) throw status;
            throw new IllegalStateException("No se pudo completar la operación", e);
        }
    }

    /**
     * 5. CONFIGURAR / CONSULTAR SERVIDOR DE CORREO SALIENTE SMTP EN CALIENTE
     */
    @GetMapping("/config/smtp")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<?> obtenerEstadoSmtp() {
        return ResponseEntity.ok(Map.of(
                "host", emailService.getSmtpHost(),
                "port", emailService.getSmtpPort(),
                "username", emailService.getSmtpUsername() != null ? emailService.getSmtpUsername() : "",
                "configurado", emailService.isSmtpConfigurado(),
                "proveedor", emailService.getMailProvider(),
                "correoConfigurado", emailService.isCorreoConfigurado()
        ));
    }

    @PostMapping("/config/smtp")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<?> configurarSmtp(@RequestBody Map<String, Object> body) {
        String host = Objects.toString(body.getOrDefault("host", "smtp.gmail.com"), "smtp.gmail.com");
        int port = Integer.parseInt(Objects.toString(body.getOrDefault("port", "587"), "587"));
        String username = Objects.toString(body.getOrDefault("username", ""), "");
        String password = Objects.toString(body.getOrDefault("password", ""), "");

        emailService.actualizarCredencialesSmtp(host, port, username, password);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "configurado", emailService.isSmtpConfigurado(),
                "mensaje", "Servidor SMTP configurado con éxito (" + username + "). Ahora los correos saldrán directamente a cualquier destinatario."
        ));
    }
}
