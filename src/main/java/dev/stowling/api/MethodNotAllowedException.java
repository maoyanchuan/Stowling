package dev.stowling.api;

final class MethodNotAllowedException extends Exception {
    private final String allowedMethods;

    MethodNotAllowedException(String allowedMethods) {
        super("Method not allowed");
        this.allowedMethods = allowedMethods;
    }

    String allowedMethods() {
        return allowedMethods;
    }
}
