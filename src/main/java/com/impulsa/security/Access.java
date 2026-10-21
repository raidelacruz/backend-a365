package com.impulsa.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component("access")
public class Access {
    private final JdbcTemplate jdbc;
    public Access(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public SessionUser current() { return (SessionUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal(); }
    public boolean isSelf(Integer id, Authentication auth) { return auth != null && auth.getPrincipal() instanceof SessionUser user && id != null && user.id() == id; }
    public boolean isSelfOrAdmin(Integer id, Authentication auth) { return isSelf(id, auth) || role(auth, "ADMINISTRADOR"); }
    public boolean role(Authentication auth, String role) { return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role)); }
    public boolean staff(Authentication auth) { return role(auth, "ADMINISTRADOR") || role(auth, "SUPERVISOR") || role(auth, "EMPLEADO"); }
    public boolean canReadTicket(Long id, Authentication auth) {
        if (staff(auth)) return true;
        if (auth == null || !(auth.getPrincipal() instanceof SessionUser user)) return false;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE id_ticket = ? AND id_usuario_creador = ?", Integer.class, id, user.id());
        return count != null && count > 0;
    }
}
