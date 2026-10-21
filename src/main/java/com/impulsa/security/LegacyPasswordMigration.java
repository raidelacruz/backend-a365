package com.impulsa.security;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

@Component
@ConditionalOnProperty(name = "app.security.migrate-passwords", havingValue = "true", matchIfMissing = true)
public class LegacyPasswordMigration implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordService passwords;
    public LegacyPasswordMigration(JdbcTemplate jdbc, PasswordService passwords) { this.jdbc = jdbc; this.passwords = passwords; }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        for (var user : jdbc.queryForList("SELECT id_usuario, password_hash FROM usuarios")) {
            String old = Objects.toString(user.get("password_hash"), "");
            if (!passwords.encoded(old)) jdbc.update(
                "UPDATE usuarios SET password_hash = ?, actualizado_en = CURRENT_TIMESTAMP WHERE id_usuario = ? AND password_hash = ?",
                passwords.migrate(old), user.get("id_usuario"), old);
        }
    }
}
