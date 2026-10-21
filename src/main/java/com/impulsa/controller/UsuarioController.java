package com.impulsa.controller;

import com.impulsa.security.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.text.Normalizer;

@RestController
@RequestMapping("/api/usuarios")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class UsuarioController {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordService passwords;

    /** Catálogo inicial más las áreas existentes, sin modificar registros históricos. */
    @GetMapping("/departamentos")
    public ResponseEntity<List<String>> listarDepartamentos() {
        Set<String> departamentos = new LinkedHashSet<>(List.of(
                "Tecnología", "Soporte", "Finanzas", "Operaciones", "RRHH", "Externo", "General"));
        List<String> existentes = jdbcTemplate.queryForList("""
                SELECT DISTINCT TRIM(departamento) AS nombre
                FROM usuarios
                WHERE departamento IS NOT NULL AND TRIM(departamento) <> ''
                UNION
                SELECT DISTINCT TRIM(area_departamento) AS nombre
                FROM empleados
                WHERE area_departamento IS NOT NULL AND TRIM(area_departamento) <> ''
                ORDER BY nombre
                """, String.class);
        for (String existente : existentes) {
            if (departamentos.stream().noneMatch(nombre -> normalizarDepartamento(nombre).equals(normalizarDepartamento(existente)))) {
                departamentos.add(existente);
            }
        }
        return ResponseEntity.ok(List.copyOf(departamentos));
    }

    private String normalizarDepartamento(String nombre) {
        return Normalizer.normalize(nombre.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 1. LISTAR TODOS LOS USUARIOS CON SU ROL (Directorio Admin)
     */
    @GetMapping
    public ResponseEntity<?> listarUsuarios() {
        String sql = """
                SELECT u.id_usuario AS "idUsuario",
                       u.codigo_usuario AS "codigoUsuario",
                       u.correo,
                       u.nombres,
                       u.apellidos,
                       u.dni_ruc AS "dniRuc",
                       u.telefono,
                       u.departamento,
                       u.cargo,
                       u.doble_factor_activo AS "dobleFactorActivo",
                       u.alerta_login_activa AS "alertaLoginActiva",
                       u.activo,
                       r.id_rol AS "idRol",
                       r.nombre AS rol
                FROM usuarios u
                JOIN roles r ON u.id_rol = r.id_rol
                ORDER BY u.id_usuario ASC
                """;
        return ResponseEntity.ok(jdbcTemplate.queryForList(sql));
    }

    /**
     * 2. OBTENER UN USUARIO POR ID (Para perfil.html y configuracion.html)
     */
    @GetMapping("/{id}")
    @PreAuthorize("@access.isSelfOrAdmin(#id, authentication)")
    public ResponseEntity<?> obtenerUsuario(@PathVariable Integer id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT u.id_usuario AS "idUsuario",
                       u.codigo_usuario AS "codigoUsuario",
                       u.correo,
                       u.nombres,
                       u.apellidos,
                       u.dni_ruc AS "dniRuc",
                       u.telefono,
                       u.departamento,
                       u.cargo,
                       u.doble_factor_activo AS "dobleFactorActivo",
                       u.alerta_login_activa AS "alertaLoginActiva",
                       u.activo,
                       r.nombre AS rol,
                       COALESCE(c.direccion, 'Lima, Perú') AS direccion
                FROM usuarios u
                JOIN roles r ON u.id_rol = r.id_rol
                LEFT JOIN clientes c ON u.id_usuario = c.id_usuario
                WHERE u.id_usuario = ?
                """, id);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Usuario no encontrado"));
        }
        return ResponseEntity.ok(rows.get(0));
    }

    /**
     * 3. LISTAR ROLES DISPONIBLES
     */
    @GetMapping("/roles")
    public ResponseEntity<?> listarRoles() {
        String sql = "SELECT id_rol AS \"idRol\", nombre, descripcion, nivel_acceso AS \"nivelAcceso\" FROM roles ORDER BY nivel_acceso DESC";
        return ResponseEntity.ok(jdbcTemplate.queryForList(sql));
    }

    /**
     * 4. CREAR NUEVO USUARIO Y ASIGNARLE ROL (Exclusivo Administrador)
     */
    @PostMapping
    @Transactional
    public ResponseEntity<?> crearUsuario(@RequestBody Map<String, Object> body) {
        try {
            String nombres = Objects.toString(body.get("nombres"), "").trim();
            String apellidos = Objects.toString(body.getOrDefault("apellidos", ""), "").trim();
            String correo = Objects.toString(body.get("correo"), "").trim().toLowerCase();
            String password = passwords.encode(Objects.toString(body.get("password"), ""));
            String rolInput = Objects.toString(body.getOrDefault("rol", "EMPLEADO"), "EMPLEADO").trim().toUpperCase();
            String departamento = Objects.toString(body.getOrDefault("departamento", "Soporte"), "Soporte").trim();
            String dni = Objects.toString(body.getOrDefault("dni", ""), "").trim();
            String telefono = Objects.toString(body.getOrDefault("telefono", ""), "").trim();

            if (nombres.isEmpty() || correo.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Nombres y correo son obligatorios."));
            }

            Integer existe = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM usuarios WHERE LOWER(correo) = ?", Integer.class, correo);
            if (existe != null && existe > 0) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Ya existe un usuario con ese correo electrónico."));
            }

            String rolNormalizado = "EMPLEADO";
            String cargoDefault = "Agente Soporte";
            if (rolInput.contains("ADMIN")) {
                rolNormalizado = "ADMINISTRADOR";
                cargoDefault = "Administrador";
            } else if (rolInput.contains("SUPER")) {
                rolNormalizado = "SUPERVISOR";
                cargoDefault = "Supervisor";
            } else if (rolInput.contains("CLI")) {
                rolNormalizado = "CLIENTE";
                cargoDefault = "Cliente";
            }

            Integer idRol = jdbcTemplate.queryForObject(
                    "SELECT id_rol FROM roles WHERE nombre = ? LIMIT 1", Integer.class, rolNormalizado);
            if (idRol == null) idRol = 3;

            Integer maxId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id_usuario), 100) FROM usuarios", Integer.class);
            String prefijo = rolNormalizado.equals("CLIENTE") ? "CLI-" : "USR-";
            String codigoUsuario = String.format("%s%05d", prefijo, (maxId != null ? maxId + 101 : 106));

            String sqlInsert = """
                    INSERT INTO usuarios (codigo_usuario, correo, password_hash, nombres, apellidos, dni_ruc, telefono, departamento, cargo, id_rol, doble_factor_activo, alerta_login_activa, activo)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, TRUE, TRUE)
                    RETURNING id_usuario
                    """;

            Integer idUsuario = jdbcTemplate.queryForObject(sqlInsert, Integer.class,
                    codigoUsuario, correo, password, nombres, apellidos, dni, telefono, departamento, cargoDefault, idRol);

            if (rolNormalizado.equals("CLIENTE")) {
                jdbcTemplate.update("""
                        INSERT INTO clientes (id_usuario, tipo_documento, numero_documento, razon_social, contacto_nombre, telefono)
                        VALUES (?, 'DNI', ?, ?, ?, ?)
                        """, idUsuario, dni.isEmpty() ? "00000000" : dni, (nombres + " " + apellidos).trim(), nombres, telefono);
            } else {
                jdbcTemplate.update("""
                        INSERT INTO empleados (id_usuario, codigo_empleado, dni, nombres, apellidos, telefono, area_departamento, cargo)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, idUsuario, codigoUsuario, dni.isEmpty() ? "00000000" : dni, nombres, apellidos, telefono, departamento, cargoDefault);
            }

            return ResponseEntity.status(201).body(Map.of(
                    "success", true,
                    "mensaje", "Usuario registrado con código " + codigoUsuario + " y rol " + rolNormalizado,
                    "codigoUsuario", codigoUsuario,
                    "idUsuario", idUsuario
            ));
        } catch (Exception e) {
            if (e instanceof org.springframework.web.server.ResponseStatusException status) throw status;
            throw new IllegalStateException("No se pudo completar la operación", e);
        }
    }

    /**
     * 5. EDITAR DATOS Y ROL DE UN USUARIO (Exclusivo Administrador)
     */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> actualizarUsuario(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        try {
            String nombres = Objects.toString(body.getOrDefault("nombres", ""), "").trim();
            String apellidos = Objects.toString(body.getOrDefault("apellidos", ""), "").trim();
            String correo = Objects.toString(body.getOrDefault("correo", ""), "").trim().toLowerCase();
            String rolInput = Objects.toString(body.getOrDefault("rol", "EMPLEADO"), "").trim().toUpperCase();
            String departamento = Objects.toString(body.getOrDefault("departamento", "Soporte"), "").trim();
            String telefono = Objects.toString(body.getOrDefault("telefono", ""), "").trim();

            String rolNormalizado = "EMPLEADO";
            String cargo = "Agente Soporte";
            if (rolInput.contains("ADMIN")) {
                rolNormalizado = "ADMINISTRADOR";
                cargo = "Administradora";
            } else if (rolInput.contains("SUPER")) {
                rolNormalizado = "SUPERVISOR";
                cargo = "Supervisor";
            } else if (rolInput.contains("CLI")) {
                rolNormalizado = "CLIENTE";
                cargo = "Cliente";
            }

            Integer idRol = jdbcTemplate.queryForObject(
                    "SELECT id_rol FROM roles WHERE nombre = ? LIMIT 1", Integer.class, rolNormalizado);
            if (idRol == null) idRol = 3;

            jdbcTemplate.update("""
                    UPDATE usuarios
                    SET nombres = ?, apellidos = ?, correo = ?, id_rol = ?, departamento = ?, cargo = ?,
                        telefono = COALESCE(NULLIF(?, ''), telefono), actualizado_en = CURRENT_TIMESTAMP
                    WHERE id_usuario = ?
                    """, nombres, apellidos, correo, idRol, departamento, cargo, telefono, id);

            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Usuario y rol actualizados correctamente."));
        } catch (Exception e) {
            if (e instanceof org.springframework.web.server.ResponseStatusException status) throw status;
            throw new IllegalStateException("No se pudo completar la operación", e);
        }
    }

    /**
     * 6. ACTUALIZAR MI PROPIO PERFIL (Desde perfil.html)
     */
    @PutMapping("/{id}/perfil")
    @PreAuthorize("@access.isSelf(#id, authentication)")
    @Transactional
    public ResponseEntity<?> actualizarPerfilPropio(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        try {
            String nombreCompleto = Objects.toString(body.getOrDefault("nombreCompleto", ""), "").trim();
            String correo = Objects.toString(body.getOrDefault("correo", ""), "").trim().toLowerCase();
            String telefono = Objects.toString(body.getOrDefault("telefono", ""), "").trim();
            String dniRuc = Objects.toString(body.getOrDefault("dniRuc", ""), "").trim();

            String nombres = nombreCompleto;
            String apellidos = "";
            int spaceIdx = nombreCompleto.indexOf(' ');
            if (spaceIdx > 0) {
                nombres = nombreCompleto.substring(0, spaceIdx).trim();
                apellidos = nombreCompleto.substring(spaceIdx + 1).trim();
            }

            jdbcTemplate.update("""
                    UPDATE usuarios
                    SET nombres = ?, apellidos = ?, correo = ?, telefono = ?, dni_ruc = ?, actualizado_en = CURRENT_TIMESTAMP
                    WHERE id_usuario = ?
                    """, nombres, apellidos, correo, telefono, dniRuc, id);

            jdbcTemplate.update("""
                    UPDATE empleados
                    SET nombres = ?, apellidos = ?, telefono = ?, dni = ?
                    WHERE id_usuario = ?
                    """, nombres, apellidos, telefono, dniRuc, id);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "nombres", nombres,
                    "apellidos", apellidos,
                    "correo", correo,
                    "telefono", telefono,
                    "dniRuc", dniRuc,
                    "mensaje", "Perfil actualizado exitosamente en Supabase."
            ));
        } catch (Exception e) {
            if (e instanceof org.springframework.web.server.ResponseStatusException status) throw status;
            throw new IllegalStateException("No se pudo completar la operación", e);
        }
    }

    /**
     * 7. CAMBIAR CONTRASEÑA DESDE CONFIGURACION.HTML
     */
    @PostMapping("/{id}/cambiar-clave")
    @PreAuthorize("@access.isSelf(#id, authentication)")
    @Transactional
    public ResponseEntity<?> cambiarClavePropia(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        try {
            String actual = Objects.toString(body.get("passwordActual"), "");
            String nueva = Objects.toString(body.get("nuevaPassword"), "");
            passwords.validate(nueva);

            String passDb = jdbcTemplate.queryForObject(
                    "SELECT password_hash FROM usuarios WHERE id_usuario = ?", String.class, id);

            if (!passwords.matches(actual, passDb)) {
                return ResponseEntity.status(400).body(Map.of("success", false, "error", "La contraseña actual ingresada no es correcta."));
            }

            jdbcTemplate.update("UPDATE usuarios SET password_hash = ?, actualizado_en = CURRENT_TIMESTAMP WHERE id_usuario = ?", passwords.encode(nueva), id);
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Contraseña actualizada correctamente en Supabase."));
        } catch (Exception e) {
            if (e instanceof org.springframework.web.server.ResponseStatusException status) throw status;
            throw new IllegalStateException("No se pudo completar la operación", e);
        }
    }

    /**
     * 8. OBTENER SESIONES Y ALERTAS DE ACCESO DE UN USUARIO (Para configuracion.html)
     */
    @GetMapping("/{id}/sesiones")
    @PreAuthorize("@access.isSelf(#id, authentication)")
    public ResponseEntity<?> listarSesionesUsuario(@PathVariable Integer id) {
        String sql = """
                SELECT id_sesion AS "idSesion",
                       ip_acceso AS ip,
                       dispositivo,
                       correo_alerta_enviado AS "alertaEnviada",
                       TO_CHAR(fecha_acceso, 'YYYY-MM-DD HH24:MI:SS') AS fecha
                FROM sesiones_alertas
                WHERE id_usuario = ?
                ORDER BY id_sesion DESC
                LIMIT 10
                """;
        return ResponseEntity.ok(jdbcTemplate.queryForList(sql, id));
    }

    /**
     * 9. GUARDAR PREFERENCIAS DE SEGURIDAD (2FA y Alertas de Login por Correo)
     */
    @PatchMapping("/{id}/preferencias")
    @PreAuthorize("@access.isSelf(#id, authentication)")
    public ResponseEntity<?> actualizarPreferencias(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        try {
            Boolean alertaLogin = Boolean.valueOf(Objects.toString(body.getOrDefault("alertaLoginActiva", "true")));
            Boolean dobleFactor = Boolean.valueOf(Objects.toString(body.getOrDefault("dobleFactorActivo", "true")));
            jdbcTemplate.update("""
                    UPDATE usuarios
                    SET alerta_login_activa = ?, doble_factor_activo = ?, actualizado_en = CURRENT_TIMESTAMP
                    WHERE id_usuario = ?
                    """, alertaLogin, dobleFactor, id);
            return ResponseEntity.ok(Map.of("success", true, "mensaje", "Preferencias de seguridad y correo actualizadas en Supabase."));
        } catch (Exception e) {
            if (e instanceof org.springframework.web.server.ResponseStatusException status) throw status;
            throw new IllegalStateException("No se pudo completar la operación", e);
        }
    }

    /**
     * 10. ACTIVAR / DESACTIVAR CUENTA DE USUARIO
     */
    @PatchMapping("/{id}/estado")
    public ResponseEntity<?> cambiarEstadoUsuario(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        try {
            Boolean activo = Boolean.valueOf(Objects.toString(body.get("activo"), "true"));
            jdbcTemplate.update("UPDATE usuarios SET activo = ?, actualizado_en = CURRENT_TIMESTAMP WHERE id_usuario = ?", activo, id);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "activo", activo,
                    "mensaje", activo ? "Cuenta de usuario activada con éxito." : "Cuenta de usuario desactivada."
            ));
        } catch (Exception e) {
            if (e instanceof org.springframework.web.server.ResponseStatusException status) throw status;
            throw new IllegalStateException("No se pudo completar la operación", e);
        }
    }
}
