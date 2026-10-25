package com.impulsa.dao;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ClienteDao {
    private final JdbcTemplate jdbc;
    public ClienteDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Map<String,Object>> list(String query, Boolean active, LocalDate from, LocalDate to) {
        String term = "%" + query + "%";
        return jdbc.queryForList("""
            SELECT c.id_cliente AS "idCliente", c.id_usuario AS "idUsuario", u.codigo_usuario AS codigo,
                c.razon_social AS "razonSocial", c.tipo_documento AS "tipoDocumento", c.numero_documento AS documento,
                c.contacto_nombre AS "contactoNombre", c.telefono, c.direccion, u.correo, u.activo,
                TO_CHAR(c.creado_en, 'YYYY-MM-DD') AS fecha
            FROM clientes c JOIN usuarios u ON u.id_usuario = c.id_usuario
            JOIN roles r ON r.id_rol = u.id_rol
            WHERE r.nombre = 'CLIENTE'
              AND (c.razon_social ILIKE ? OR c.numero_documento ILIKE ? OR u.codigo_usuario ILIKE ? OR u.correo ILIKE ? OR c.contacto_nombre ILIKE ?)
              AND (?::boolean IS NULL OR u.activo = ?::boolean)
              AND (?::date IS NULL OR c.creado_en >= ?::date)
              AND (?::date IS NULL OR c.creado_en < ?::date + INTERVAL '1 day')
            ORDER BY c.id_cliente DESC LIMIT 500
            """, term, term, term, term, term, active, active, from, from, to, to);
    }
    public int createAccount(Map<String,String> data, String hash, String code) {
        return jdbc.queryForObject("""
            INSERT INTO usuarios (codigo_usuario, correo, password_hash, nombres, apellidos, dni_ruc, telefono, departamento, cargo, id_rol)
            VALUES (?, ?, ?, ?, '', ?, ?, 'Externo', 'Cliente', (SELECT id_rol FROM roles WHERE nombre = 'CLIENTE')) RETURNING id_usuario
            """, Integer.class, code, data.get("correo"), hash, data.get("contactoNombre"), data.get("documento"), data.get("telefono"));
    }
    public long createClient(int user, Map<String,String> data) {
        return jdbc.queryForObject("""
            INSERT INTO clientes (id_usuario, tipo_documento, numero_documento, razon_social, contacto_nombre, telefono, direccion)
            VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id_cliente
            """, Long.class, user, data.get("tipoDocumento"), data.get("documento"), data.get("razonSocial"), data.get("contactoNombre"), data.get("telefono"), data.get("direccion"));
    }
    public Integer lockUser(long id) {
        var rows = jdbc.queryForList("SELECT id_usuario FROM clientes WHERE id_cliente = ? FOR UPDATE", id);
        return rows.isEmpty() ? null : ((Number)rows.get(0).get("id_usuario")).intValue();
    }
    public void update(long id, int user, Map<String,String> data) {
        jdbc.update("""
            UPDATE clientes SET tipo_documento = ?, numero_documento = ?, razon_social = ?, contacto_nombre = ?, telefono = ?, direccion = ?
            WHERE id_cliente = ?
            """, data.get("tipoDocumento"), data.get("documento"), data.get("razonSocial"), data.get("contactoNombre"), data.get("telefono"), data.get("direccion"), id);
        jdbc.update("UPDATE usuarios SET correo = ?, dni_ruc = ?, telefono = ?, actualizado_en = CURRENT_TIMESTAMP WHERE id_usuario = ?",
                data.get("correo"), data.get("documento"), data.get("telefono"), user);
    }
}
