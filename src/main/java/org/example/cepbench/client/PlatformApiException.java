package org.example.cepbench.client;

/**
 * A platform API call that did not succeed.
 *
 * <p>Carries the status and a truncated body because the platform reports validation failures, name
 * collisions and missing references all as ordinary error responses, and the body is usually the
 * only thing that says which.
 */
public class PlatformApiException extends RuntimeException {

    private static final int MAX_BODY_CHARS = 1_000;

    private final int statusCode;

    public PlatformApiException(String method, String path, int statusCode, String body) {
        super("%s %s failed with HTTP %d: %s".formatted(method, path, statusCode, truncate(body)));
        this.statusCode = statusCode;
    }

    public PlatformApiException(String method, String path, Throwable cause) {
        super("%s %s failed: %s".formatted(method, path, cause.toString()), cause);
        this.statusCode = -1;
    }

    /** HTTP status, or -1 when the request never produced a response. */
    public int statusCode() {
        return statusCode;
    }

    public boolean isNotFound() {
        return statusCode == 404;
    }

    public boolean isConflict() {
        return statusCode == 409;
    }

    private static String truncate(String body) {
        if (body == null || body.isBlank()) {
            return "<empty body>";
        }
        String collapsed = body.strip().replaceAll("\\s+", " ");
        return collapsed.length() <= MAX_BODY_CHARS
                ? collapsed
                : collapsed.substring(0, MAX_BODY_CHARS) + "... (truncated)";
    }
}
