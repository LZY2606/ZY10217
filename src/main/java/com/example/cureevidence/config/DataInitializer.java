package com.example.cureevidence.config;

import com.example.cureevidence.service.DatabaseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer {
    private final DatabaseService databaseService;
    private final boolean autoImport;

    public DataInitializer(DatabaseService databaseService,
                           @Value("${app.auto-import:true}") boolean autoImport) {
        this.databaseService = databaseService;
        this.autoImport = autoImport;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        if (autoImport && databaseService.isEmpty()) {
            databaseService.resetAndImportFixedFixture();
        }
    }
}
