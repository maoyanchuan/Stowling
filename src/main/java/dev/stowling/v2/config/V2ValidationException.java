package dev.stowling.v2.config;

public final class V2ValidationException extends IllegalArgumentException {
    public V2ValidationException(String message) {
        super(message);
    }

    public V2ValidationException(String message, Throwable cause) {
        super(message, cause);
    }

    static String diagnosticMessage(RuntimeException exception) {
        // Gson's record constructor wrapper includes all arguments in its message.
        Throwable cause = exception.getCause();
        Throwable diagnostic = exception.getClass() == RuntimeException.class && cause != null
                ? cause : exception;
        String message = diagnostic.getMessage();
        return message == null || message.isBlank()
                ? diagnostic.getClass().getSimpleName() : message;
    }
}
