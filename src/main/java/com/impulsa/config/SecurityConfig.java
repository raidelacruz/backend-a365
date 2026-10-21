package com.impulsa.config;

import com.impulsa.security.AccountService;
import com.impulsa.security.ActiveAccountFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.http.HttpMethod;
import jakarta.servlet.http.HttpServletResponse;

@Configuration @EnableMethodSecurity
public class SecurityConfig {
    @Bean public HttpSessionSecurityContextRepository contextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean public HttpSessionCsrfTokenRepository csrfRepository() { return new HttpSessionCsrfTokenRepository(); }
    private void error(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"error\":\"" + message + "\"}");
    }
    @Bean public SecurityFilterChain filterChain(HttpSecurity http, AccountService accounts) throws Exception {
        http.cors(cors -> {})
            .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository()))
            .securityContext(context -> context.securityContextRepository(contextRepository()))
            .requestCache(cache -> cache.disable())
            .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/verify-2fa", "/api/auth/register-cliente", "/api/auth/recuperar-clave", "/api/auth/restablecer-clave").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, ex) -> error(res, 401, "Inicia sesión para continuar."))
                .accessDeniedHandler((req, res, ex) -> error(res, 403, "No tienes permiso o la verificación de seguridad venció. Actualiza la página.")))
            .logout(logout -> logout.logoutUrl("/api/auth/logout").invalidateHttpSession(true).clearAuthentication(true).deleteCookies("A365SESSION")
                .logoutSuccessHandler((req, res, auth) -> { res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"success\":true}"); }))
            .addFilterBefore(new ActiveAccountFilter(accounts), AuthorizationFilter.class);
        return http.build();
    }
}
