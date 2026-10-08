package com.mtcc.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretRedactorTest {

    private final SecretRedactor secretRedactor = new SecretRedactor();

    @Test
    void redact_ShouldRedactSensitiveValuesInPropertiesFiles() {
        final Redaction redaction = secretRedactor.redact("""
                spring.datasource.url=jdbc:postgresql://localhost:5432/app
                spring.datasource.username=app
                spring.datasource.password=s3cr3t!
                GITHUB.API.KEY=abc123
                server.port=8080
                """, true);

        assertEquals("""
                spring.datasource.url=jdbc:postgresql://localhost:5432/app
                spring.datasource.username=app
                spring.datasource.password=[REDACTED]
                GITHUB.API.KEY=[REDACTED]
                server.port=8080
                """, redaction.getText());
        assertEquals(2, redaction.getCount());
    }

    @Test
    void redact_ShouldRedactSensitiveValuesInYamlAndJson() {
        final Redaction yaml = secretRedactor.redact("""
                datasource:
                  username: app
                  password: "s3cr3t"
                  credentials:
                    client-secret: 'abc'
                """, true);
        final Redaction json = secretRedactor.redact("""
                {
                  "user": "app",
                  "apiKey": "abc123",
                  "timeout": 30
                }
                """, true);

        assertEquals("""
                datasource:
                  username: app
                  password: "[REDACTED]"
                  credentials:
                    client-secret: '[REDACTED]'
                """, yaml.getText());
        assertEquals("""
                {
                  "user": "app",
                  "apiKey": "[REDACTED]",
                  "timeout": 30
                }
                """, json.getText());
    }

    @Test
    void redact_ShouldRedactSensitiveValuesInXml() {
        final Redaction redaction = secretRedactor.redact("""
                <server>
                  <username>deploy</username>
                  <password>s3cr3t</password>
                  <user name="admin" password="admin123" authorities="ROLE_ADMIN"/>
                </server>
                """, true);

        assertEquals("""
                <server>
                  <username>deploy</username>
                  <password>[REDACTED]</password>
                  <user name="admin" password="[REDACTED]" authorities="ROLE_ADMIN"/>
                </server>
                """, redaction.getText());
        assertEquals(2, redaction.getCount());
    }

    @Test
    void redact_ShouldKeepPlaceholders() {
        final String configuration = """
                spring.datasource.password=${DB_PASSWORD}
                api.key={{ vault.api_key }}
                client.secret=<your-secret-here>
                token.enabled=true
                """;

        final Redaction redaction = secretRedactor.redact(configuration, true);

        assertEquals(configuration, redaction.getText());
        assertEquals(0, redaction.getCount());
    }

    @Test
    void redact_ShouldRedactKnownTokensAndUrlCredentialsInAnyFile() {
        final Redaction redaction = secretRedactor.redact("""
                String key = "AKIAIOSFODNN7EXAMPLE";
                String url = "mongodb://admin:hunter2@db.internal:27017/app";
                String password = request.getParameter("password");
                """, false);

        assertEquals("""
                String key = "[REDACTED]";
                String url = "mongodb://admin:[REDACTED]@db.internal:27017/app";
                String password = request.getParameter("password");
                """, redaction.getText());
        assertEquals(2, redaction.getCount());
    }

    @Test
    void redact_ShouldRedactPrivateKeys() {
        final Redaction redaction = secretRedactor.redact("""
                -----BEGIN RSA PRIVATE KEY-----
                MIIEowIBAAKCAQEAxyz
                -----END RSA PRIVATE KEY-----
                """, false);

        assertEquals("[REDACTED]\n", redaction.getText());
    }

    @Test
    void containsSecretToken_ShouldDetectKnownTokenFormatsOnly() {
        assertTrue(secretRedactor.containsSecretToken("the key is ghp_abcdefghijklmnopqrstuvwxyz0123456789"));
        assertFalse(secretRedactor.containsSecretToken("set spring.datasource.password in application.properties"));
    }
}
