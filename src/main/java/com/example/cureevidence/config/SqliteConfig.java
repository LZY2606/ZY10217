package com.example.cureevidence.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class SqliteConfig {
    @Bean
    public DataSource dataSource(@Value("${app.db.path}") String dbPath) {
        Path path = Path.of(dbPath);
        if (path.getParent() != null) {
            try {
                Files.createDirectories(path.getParent());
            } catch (Exception ex) {
                throw new IllegalStateException("Cannot create SQLite directory: " + path.getParent(), ex);
            }
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + path.toAbsolutePath());
        return dataSource;
    }
}
