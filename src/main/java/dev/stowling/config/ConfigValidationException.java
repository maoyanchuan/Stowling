package dev.stowling.config;

import java.util.List;

public final class ConfigValidationException extends IllegalArgumentException {
    private final List<ConfigViolation> violations;

    public ConfigValidationException(List<ConfigViolation> violations) {
        super(createMessage(violations));
        this.violations = List.copyOf(violations);
    }

    public List<ConfigViolation> violations() {
        return violations;
    }

    private static String createMessage(List<ConfigViolation> violations) {
        StringBuilder message = new StringBuilder("Invalid Stowling configuration:");
        for (ConfigViolation violation : violations) {
            message.append(System.lineSeparator())
                    .append("- ")
                    .append(violation.path())
                    .append(": ")
                    .append(violation.message());
        }
        return message.toString();
    }
}
