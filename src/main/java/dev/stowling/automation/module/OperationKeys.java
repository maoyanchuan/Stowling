package dev.stowling.automation.module;

import dev.stowling.automation.AutomationModuleContext;
import dev.stowling.automation.AutomationStep;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class OperationKeys {
    private OperationKeys() {
    }

    static String forStep(AutomationModuleContext context) {
        AutomationStep step = context.step();
        StringBuilder canonical = new StringBuilder();
        step.parameters().keySet().stream()
                .sorted()
                .forEach(key -> canonical.append(key)
                .append('=')
                .append(step.parameters().get(key))
                .append('\n'));
        String digest = sha256(canonical.toString()).substring(0, 24);
        return context.taskId()
                + ":"
                + step.type()
                + ":"
                + step.id()
                + ":"
                + digest;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
