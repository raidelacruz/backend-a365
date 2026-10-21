package com.impulsa.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;

@Service
public class PasswordService {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final String dummy = encoder.encode("unavailable-account-placeholder");
    public boolean encoded(String value) { return value != null && value.matches("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$"); }
    public boolean matches(String raw, String hash) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 72) return false;
        return encoder.matches(raw, encoded(hash) ? hash : dummy) && encoded(hash);
    }
    public void validate(String raw) {
        if (raw == null || raw.length() < 8 || raw.getBytes(StandardCharsets.UTF_8).length > 72 || raw.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contraseña debe tener al menos 8 caracteres y no superar 72 bytes.");
    }
    public String encode(String raw) { validate(raw); return encoder.encode(raw); }
    public String migrate(String raw) {
        // Conserva las claves antiguas cortas; las nuevas sí deben cumplir la política.
        if (raw.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalStateException("Una credencial histórica necesita restablecimiento antes de migrar.");
        return encoder.encode(raw);
    }
}
