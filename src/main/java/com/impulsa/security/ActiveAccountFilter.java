package com.impulsa.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

/** Comprueba baja de cuenta, contraseña y rol vigentes antes de autorizar cada solicitud. */
public class ActiveAccountFilter extends OncePerRequestFilter {
    private final AccountService accounts;
    public ActiveAccountFilter(AccountService accounts) { this.accounts = accounts; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SessionUser user) {
            try {
                var found = accounts.byId(user.id());
                if (found.isEmpty() || !Boolean.TRUE.equals(found.get().get("activo")) || !accounts.hash(found.get()).equals(user.credentialVersion())) {
                    if (request.getSession(false) != null) request.getSession(false).invalidate();
                    SecurityContextHolder.clearContext();
                } else {
                    String role = String.valueOf(found.get().get("rol"));
                    var principal = new SessionUser(user.id(), role, user.credentialVersion());
                    SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
                }
            } catch (RuntimeException ex) {
                response.setStatus(503); response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"error\":\"No se pudo verificar la sesión. Inténtalo nuevamente.\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
