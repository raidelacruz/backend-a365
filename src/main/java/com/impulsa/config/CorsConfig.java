package com.impulsa.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.web.cors.*;
import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {
    @Bean public CorsConfigurationSource corsConfigurationSource(
        @Value("${app.security.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}") String origins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList());
        config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type","Accept","X-CSRF-TOKEN"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**",config);
        return source;
    }
}
