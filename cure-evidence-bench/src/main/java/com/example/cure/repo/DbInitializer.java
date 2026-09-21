package com.example.cure.repo;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

@Component
public class DbInitializer implements CommandLineRunner {

    private final DataStore dataStore;
    private final FixtureSeeder seeder;

    public DbInitializer(DataStore dataStore, FixtureSeeder seeder) {
        this.dataStore = dataStore;
        this.seeder = seeder;
    }

    @Override
    public void run(String... args) throws Exception {
        try (InputStream in = new ClassPathResource("schema/schema.sql").getInputStream()) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            ScriptUtils.executeSqlScript(dataStore.jdbc().getDataSource().getConnection(),
                    new org.springframework.core.io.ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        }
        Integer count = dataStore.jdbc().queryForObject(
                "SELECT COUNT(*) FROM spec_versions", Integer.class);
        if (count == null || count == 0) {
            seeder.seed();
        }
    }
}
