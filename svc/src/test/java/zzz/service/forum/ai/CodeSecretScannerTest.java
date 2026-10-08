package zzz.service.forum.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CodeSecretScannerTest {

    private final CodeSecretScanner scanner = new CodeSecretScanner();

    @Test
    public void redactsSensitiveAssignmentsAndKnownTokens() {
        CodeSecretScanner.ScanResult result = scanner.scan(
                "password: \"p@ss\"\napi_key = abc123\nconst token = ghp_abcdefghijklmnopqrstuvwxyz123456\n");

        assertTrue(result.isRedacted());
        assertTrue(result.getSanitizedText().contains("[REDACTED]"));
        assertTrue(result.getSanitizedText().contains("[REDACTED_TOKEN]"));
        assertFalse(result.getSanitizedText().contains("p@ss"));
        assertFalse(result.getSanitizedText().contains("ghp_abcdefghijklmnopqrstuvwxyz123456"));
    }

    @Test
    public void redactsPrivateKeyBlock() {
        CodeSecretScanner.ScanResult result = scanner.scan(
                "-----BEGIN PRIVATE KEY-----\nsecret\n-----END PRIVATE KEY-----");

        assertTrue(result.getSanitizedText().contains("[REDACTED_PRIVATE_KEY]"));
        assertFalse(result.getSanitizedText().contains("BEGIN PRIVATE KEY"));
    }

    @Test
    public void keepsSourceWithoutSensitiveValuesUnchanged() {
        String source = "public String status() { return \"ready\"; }";

        CodeSecretScanner.ScanResult result = scanner.scan(source);

        assertEquals(source, result.getSanitizedText());
        assertFalse(result.isRedacted());
        assertTrue(result.getRules().isEmpty());
    }
}
