package com.impulsa.dao;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdjuntoDao {
    private final JdbcTemplate jdbc;
    public AdjuntoDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Map<String,Object>> list(long ticket) {
        return jdbc.queryForList("""
            SELECT id_adjunto AS "idAdjunto", nombre_original AS nombre, tipo_mime AS tipo, tamano_kb AS "tamanoKb",
                TO_CHAR(creado_en, 'YYYY-MM-DD HH24:MI') AS fecha, (contenido IS NOT NULL) AS disponible
            FROM ticket_adjuntos WHERE id_ticket = ? ORDER BY id_adjunto
            """, ticket);
    }
    public Map<String,Object> lockTicket(long ticket) {
        return jdbc.queryForList("SELECT id_usuario_creador, estado FROM tickets WHERE id_ticket = ? FOR UPDATE", ticket)
                .stream().findFirst().orElse(null);
    }
    public int count(long ticket) { return jdbc.queryForObject("SELECT COUNT(*) FROM ticket_adjuntos WHERE id_ticket = ?", Integer.class, ticket); }
    public long save(long ticket, String name, String mime, byte[] bytes) {
        return jdbc.queryForObject("""
            INSERT INTO ticket_adjuntos (id_ticket, nombre_original, ruta_almacenamiento, tipo_mime, tamano_kb, contenido)
            VALUES (?, ?, ?, ?, ?, ?) RETURNING id_adjunto
            """, Long.class, ticket, name, "db:" + UUID.randomUUID(), mime, (bytes.length + 1023) / 1024, bytes);
    }
    public Map<String,Object> find(long ticket, long attachment) {
        return jdbc.queryForList("SELECT nombre_original, tipo_mime, contenido FROM ticket_adjuntos WHERE id_ticket = ? AND id_adjunto = ?", ticket, attachment)
                .stream().findFirst().orElse(null);
    }
    public void audit(long ticket, int user, String name) {
        jdbc.update("INSERT INTO ticket_historial (id_ticket, id_usuario, accion, motivo_cambio) VALUES (?, ?, 'ADJUNTO', ?)", ticket, user, name);
    }
}
