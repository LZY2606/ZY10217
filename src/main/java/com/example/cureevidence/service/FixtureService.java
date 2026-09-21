package com.example.cureevidence.service;

import com.example.cureevidence.domain.FixtureDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class FixtureService {
    private final String fixtureLocation;

    public FixtureService(@Value("${app.fixture.path:classpath:fixtures/fixed-fixture.json}") String fixtureLocation) {
        this.fixtureLocation = fixtureLocation;
    }

    public FixtureDocument load() {
        try {
            Resource resource = new org.springframework.core.io.DefaultResourceLoader().getResource(fixtureLocation);
            String raw = resource.getContentAsString(StandardCharsets.UTF_8);
            String sha = sha256(raw);
            return FixtureDocument.parse(raw, sha, "");
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot load fixed fixture: " + fixtureLocation, ex);
        }
    }
    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
