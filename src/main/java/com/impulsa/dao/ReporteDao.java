package com.impulsa.dao;

import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReporteDao {
    private final JdbcTemplate jdbc;
    public ReporteDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Map<String,Object>> tickets(LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
            SELECT t.codigo_ticket AS codigo, t.asunto, c.nombre AS categoria, t.prioridad, t.estado,
                COALESCE(t.solicitante_nombre, u.nombres) AS solicitante,
                COALESCE(a.nombres || ' ' || COALESCE(a.apellidos,''), 'Sin asignar') AS responsable,
                TO_CHAR(t.creado_en,'YYYY-MM-DD HH24:MI') AS fecha
            FROM tickets t JOIN categorias_ticket c ON c.id_categoria = t.id_categoria
            JOIN usuarios u ON u.id_usuario = t.id_usuario_creador
            LEFT JOIN empleados e ON e.id_empleado = t.id_empleado_asignado
            LEFT JOIN usuarios a ON a.id_usuario = e.id_usuario
            WHERE (?::date IS NULL OR t.creado_en >= ?::date)
              AND (?::date IS NULL OR t.creado_en < ?::date + INTERVAL '1 day')
            ORDER BY t.creado_en DESC, t.id_ticket DESC LIMIT 2001
            """, from, from, to, to);
    }
}
