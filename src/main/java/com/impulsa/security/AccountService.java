package com.impulsa.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class AccountService {
    private final JdbcTemplate jdbc;
    public AccountService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final String SELECT = """
        SELECT u.id_usuario AS "idUsuario", u.codigo_usuario AS "codigoUsuario",
               u.correo, u.nombres, u.apellidos, u.dni_ruc AS "dniRuc", u.telefono,
               u.departamento, u.cargo, u.activo, u.password_hash AS "passwordHash",
               u.doble_factor_activo AS "dobleFactorActivo", u.alerta_login_activa AS "alertaLoginActiva",
               r.nombre AS rol
        FROM usuarios u JOIN roles r ON u.id_rol = r.id_rol
        """;
    public Optional<Map<String, Object>> byLogin(String value) {
        return jdbc.queryForList(SELECT + " WHERE LOWER(u.correo) = LOWER(?) OR LOWER(u.codigo_usuario) = LOWER(?) LIMIT 1", value, value).stream().findFirst();
    }
    public Optional<Map<String, Object>> byId(int id) {
        return jdbc.queryForList(SELECT + " WHERE u.id_usuario = ?", id).stream().findFirst();
    }
    public Map<String, Object> require(int id) {
        return byId(id).filter(account -> Boolean.TRUE.equals(account.get("activo")))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tu sesión ya no está disponible. Inicia sesión nuevamente."));
    }
    public Map<String, Object> publicUser(Map<String, Object> account) {
        Map<String, Object> result = new LinkedHashMap<>(account);
        result.remove("passwordHash");
        return result;
    }
    public String hash(Map<String, Object> account) { return Objects.toString(account.get("passwordHash"), ""); }
    public int id(Map<String, Object> account) { return ((Number) account.get("idUsuario")).intValue(); }
    public String name(Map<String, Object> account) {
        return (Objects.toString(account.get("nombres"), "") + " " + Objects.toString(account.get("apellidos"), "")).trim();
    }
}
