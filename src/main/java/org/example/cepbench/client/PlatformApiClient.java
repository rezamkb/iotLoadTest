package org.example.cepbench.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.cepbench.config.BenchmarkConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin client over the platform REST API, covering only what the benchmark needs.
 *
 * <p>Two rules govern retries, and the distinction matters more than it looks:
 *
 * <ul>
 *   <li><b>GET, PUT and DELETE are retried</b> on transient transport failures and 5xx. They are
 *       idempotent, and a provision run makes thousands of calls over several minutes, so a single
 *       dropped connection should not abort it.</li>
 *   <li><b>POST is never retried.</b> A create that times out may well have succeeded on the server.
 *       Retrying it risks a second resource that no manifest records and that cleanup will therefore
 *       never delete. Losing the run is recoverable; leaking orphans on a shared sandbox is not.</li>
 * </ul>
 *
 * <p>The bearer token is held in memory only and never appears in an exception message or log line.
 */
public final class PlatformApiClient {

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(500);
    private static final int LIST_PAGE_SIZE = 200;
    private static final int MAX_LIST_PAGES = 500;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String token;
    private final Duration requestTimeout;

    public PlatformApiClient(BenchmarkConfig.PlatformTarget target) {
        this.baseUrl = target.apiBaseUrl();
        this.token = target.token();
        this.requestTimeout = target.requestTimeout();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(target.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ---------------------------------------------------------------- device types

    /**
     * @param attributes attribute name to platform type, for example {@code temp -> number}
     */
    public String createDeviceType(String name, String description, Map<String, String> attributes) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("description", description);
        body.put("encryptionEnabled", false);
        body.put("protocol", "mqtt");

        ArrayNode attributeTypes = body.putArray("attributeTypes");
        attributes.forEach((attributeName, type) -> {
            ObjectNode attribute = attributeTypes.addObject();
            attribute.put("name", attributeName);
            attribute.put("type", type);
        });

        return requireId(post("/device-types", body), "device-type");
    }

    // ---------------------------------------------------------------- alarm types

    public String createAlarmType(String name, String code, String description, String severity) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("code", code);
        body.put("description", description);
        body.put("severity", severity);

        return requireId(post("/alarm-types", body), "alarm-type");
    }

    // ---------------------------------------------------------------- devices

    public String createDevice(String name, String description, String deviceTypeId,
                               String serialNumber, JsonNode tags) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("description", description);
        body.put("deviceTypeId", deviceTypeId);
        body.put("serialNumber", serialNumber);
        // pushURL is deliberately omitted. It is optional, and a push target would add outbound work
        // to every event the benchmark generates.
        body.set("tags", tags);

        return requireId(post("/devices", body), "device");
    }

    // ---------------------------------------------------------------- rules

    /** Creates a rule. The platform creates rules inactive; activation is a separate call. */
    public RuleView createRule(String name, String when, JsonNode then, JsonNode tags) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("when", when);
        body.set("then", then);
        body.set("tags", tags);

        return RuleView.from(post("/rules", body));
    }

    public RuleView getRule(String ruleId) {
        return RuleView.from(send("GET", "/rules/" + encode(ruleId), null, true));
    }

    public void activateRule(String ruleId) {
        send("PUT", "/rules/" + encode(ruleId) + "/activated", objectMapper.createObjectNode(), true);
    }

    public void deactivateRule(String ruleId) {
        send("DELETE", "/rules/" + encode(ruleId) + "/activated", null, true);
    }

    // ---------------------------------------------------------------- listing

    /**
     * Reads every page of a collection, following the server's own paging.
     *
     * <p>The page index is bootstrapped from the first response rather than assumed: the platform's
     * pagination DTO defaults {@code page} to 0 but annotates it {@code @Positive}, so whether the
     * first page is 0 or 1 is not safe to guess. Starting without a {@code page} parameter and then
     * asking for {@code returnedPage + 1} works either way.
     *
     * @param query extra filters, e.g. {@code tag -> "code:82301"} or {@code name -> "..."}
     */
    public List<JsonNode> listAll(String collectionPathSegment, Map<String, String> query) {
        List<JsonNode> collected = new ArrayList<>();
        Integer nextPage = null;
        long totalElements = Long.MAX_VALUE;

        // Bounded so a server that keeps reporting another page cannot spin here forever.
        for (int request = 0; request < MAX_LIST_PAGES && collected.size() < totalElements; request++) {
            StringBuilder path = new StringBuilder("/").append(collectionPathSegment)
                    .append("?size=").append(LIST_PAGE_SIZE);
            if (nextPage != null) {
                path.append("&page=").append(nextPage);
            }
            query.forEach((key, value) ->
                    path.append('&').append(key).append('=').append(encode(value)));

            JsonNode page = send("GET", path.toString(), null, true);
            JsonNode content = page.path("content");
            if (!content.isArray() || content.isEmpty()) {
                break;
            }
            content.forEach(collected::add);

            totalElements = page.path("totalElements").isNumber()
                    ? page.path("totalElements").asLong()
                    : collected.size();
            nextPage = page.path("page").asInt(0) + 1;
        }
        return collected;
    }

    // ---------------------------------------------------------------- firings

    /**
     * Total times the platform has recorded one rule firing: the sum of {@code occurrenceCount}
     * across every alarm row raised by that rule.
     *
     * <p>Named for firings rather than alarms because the two are not the same number and the
     * difference is the whole point. Alarm rows are what {@code /alarms} returns; firings are what
     * the sentinel needs.
     *
     * <p>This is the sentinel's only source of truth. It is what separates "the producer is still
     * publishing" from "Drools is still evaluating and firing": if this stops advancing while events
     * keep being accepted, the engine has stopped, which is the production symptom.
     *
     * <p>It must be the occurrence sum, never the row count. {@code AlarmServiceImpl.raise}
     * de-duplicates on {@code (tenant, alarmType, ruleId, status=ACTIVE)}: while an alarm stays
     * ACTIVE a repeat firing bumps {@code occurrenceCount} on the existing row instead of inserting
     * a new one. A rule's {@code totalElements} therefore goes 0 -> 1 on its first firing and never
     * moves again, so a probe watching row count reports "did not fire" from the second firing
     * onwards however healthy the engine is.
     *
     * <p>Rows are summed across all pages rather than just the first: handling an alarm moves it out
     * of ACTIVE and the next firing opens a fresh row, so one rule can accumulate several. Paging is
     * driven by {@code hasNext} and a local zero-based counter, because the request parameter is
     * zero-based while this response's own {@code page} field is one-based
     * ({@code CoreDtoUtils.generateCorePage2}); deriving the next index from the response would skip
     * a page.
     */
    public long countRuleFirings(String ruleId) {
        long occurrences = 0L;

        // Bounded so a server that keeps reporting another page cannot spin here forever.
        for (int page = 0; page < MAX_LIST_PAGES; page++) {
            JsonNode body = send("GET", "/alarms?ruleId=" + encode(ruleId)
                    + "&size=" + LIST_PAGE_SIZE + "&page=" + page, null, true);

            JsonNode content = body.path("content");
            if (!content.isArray() || content.isEmpty()) {
                break;
            }
            for (JsonNode alarm : content) {
                JsonNode count = alarm.path("occurrenceCount");
                // A row without the field still stands for at least one firing, so fall back to
                // counting it rather than failing the probe: an undercount reads as a plateau, which
                // is at worst a false alarm, while an exception aborts the run.
                occurrences += count.isNumber() ? Math.max(1L, count.asLong()) : 1L;
            }
            if (!body.path("hasNext").asBoolean(false)) {
                break;
            }
        }
        return occurrences;
    }

    // ---------------------------------------------------------------- edge attachment

    /**
     * Attaches a device to an edge the operator owns.
     *
     * <p>Idempotent from the benchmark's point of view: a device the platform already reports as
     * attached is treated as success, so a resumed attach converges instead of failing.
     *
     * @return true if this call performed the attachment, false if it was already attached
     */
    public boolean attachDeviceToEdge(String edgeId, String deviceId) {
        try {
            send("POST", edgeDevicePath(edgeId, deviceId), null, false);
            return true;
        } catch (PlatformApiException e) {
            if (e.isConflict()) {
                return false;
            }
            throw e;
        }
    }

    /**
     * @return true if this call performed the detachment, false if it was not attached
     */
    public boolean detachDeviceFromEdge(String edgeId, String deviceId) {
        try {
            send("DELETE", edgeDevicePath(edgeId, deviceId), null, true);
            return true;
        } catch (PlatformApiException e) {
            if (e.isNotFound()) {
                return false;
            }
            throw e;
        }
    }

    private static String edgeDevicePath(String edgeId, String deviceId) {
        return "/edges/" + encode(edgeId) + "/devices/" + encode(deviceId);
    }

    // ---------------------------------------------------------------- deletion

    /**
     * Deletes a resource by id.
     *
     * @return true if the platform deleted it, false if it was already gone. Cleanup treats both as
     * success so that a re-run after a partial failure converges instead of erroring.
     */
    public boolean delete(String collectionPathSegment, String id) {
        try {
            send("DELETE", "/" + collectionPathSegment + "/" + encode(id), null, true);
            return true;
        } catch (PlatformApiException e) {
            if (e.isNotFound()) {
                return false;
            }
            throw e;
        }
    }

    // ---------------------------------------------------------------- plumbing

    private JsonNode post(String path, JsonNode body) {
        return send("POST", path, body, false);
    }

    private JsonNode send(String method, String path, JsonNode body, boolean retryable) {
        PlatformApiException lastFailure = null;

        for (int attempt = 1; attempt <= (retryable ? MAX_ATTEMPTS : 1); attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        buildRequest(method, path, body), HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return parse(response.body());
                }
                PlatformApiException failure =
                        new PlatformApiException(method, path, response.statusCode(), response.body());
                // 4xx will fail identically no matter how often it is retried.
                if (!retryable || response.statusCode() < 500) {
                    throw failure;
                }
                lastFailure = failure;
            } catch (IOException e) {
                PlatformApiException failure = new PlatformApiException(method, path, e);
                if (!retryable) {
                    throw failure;
                }
                lastFailure = failure;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PlatformApiException(method, path, e);
            }
            sleepBeforeRetry(attempt);
        }
        throw lastFailure;
    }

    private HttpRequest buildRequest(String method, String path, JsonNode body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");

        HttpRequest.BodyPublisher publisher = (body == null)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString());
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return builder.method(method, publisher).build();
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            // A 2xx with an unparseable body is not fatal for calls whose result we ignore, such as
            // activation, so return an empty node and let requireId complain if an id was expected.
            return objectMapper.createObjectNode();
        }
    }

    private static String requireId(JsonNode response, String what) {
        String id = response.path("id").asText("");
        if (id.isBlank()) {
            throw new IllegalStateException(
                    "Created a " + what + " but the response carried no id; body was: " + response);
        }
        return id;
    }

    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(RETRY_BACKOFF.toMillis() * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String encode(String pathSegment) {
        return java.net.URLEncoder.encode(pathSegment, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The parts of a rule the benchmark cares about. */
    public record RuleView(String id, String name, boolean activated) {

        static RuleView from(JsonNode node) {
            return new RuleView(
                    node.path("id").asText(""),
                    node.path("name").asText(""),
                    node.path("activated").asBoolean(false));
        }
    }
}
