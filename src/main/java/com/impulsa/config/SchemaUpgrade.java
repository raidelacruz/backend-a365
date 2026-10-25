package com.impulsa.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component @Order(0)
public class SchemaUpgrade implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    public SchemaUpgrade(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override @Transactional public void run(ApplicationArguments args) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V2__roles_y_adjuntos.sql"));
            return null;
        });
    }
}
