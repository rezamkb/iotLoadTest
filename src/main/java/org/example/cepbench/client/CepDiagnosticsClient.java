package org.example.cepbench.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.cepbench.config.BenchmarkConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads {@code /diagnostics/drools} straight off a CEP node.
 *
 * <p>Separate from {@link PlatformApiClient} for one reason that matters: that client attaches the
 * platform bearer token to every request, and the CEP node is a different host. Sending an API
 * credential to it would leak the credential to a service that has no business holding it. This one
 * sends no {@code Authorization} header unless the config explicitly supplies a CEP token.
 *
 * <p>It also never retries and never falls back to a default. During the failure this exists to
 * diagnose, "the node did not answer" is itself the finding, and a fabricated healthy-looking
 * response would destroy it.
 */
public final class CepDiagnosticsClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BenchmarkConfig.CepTarget target;

    public CepDiagnosticsClient(BenchmarkConfig.CepTarget target) {
        this.target = target;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(target.requestTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * @param includeFactCounts walks both sessions and counts facts per entry point. That takes the
     *                          working memory lock, so it can block behind a busy or wedged firing
     *                          thread. Leave it off while a workload is running.
     */
    public JsonNode snapshot(boolean includeFactCounts, int topEntryPoints) {
        String path = "/diagnostics/drools?facts=" + includeFactCounts + "&top=" + topEntryPoints;
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(target.diagnosticsBaseUrl() + path))
                .timeout(target.requestTimeout())
                .header("Accept", "application/json")
                .GET();
        if (!target.token().isEmpty()) {
            request.header("Authorization", "Bearer " + target.token());
        }

        try {
            HttpResponse<String> response =
                    httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new PlatformApiException("GET", path, response.statusCode(), response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new PlatformApiException("GET", path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformApiException("GET", path, e);
        }
    }

    /** Cheapest possible reading: no fact counting, so it answers even when the engine is stuck. */
    public JsonNode snapshot() {
        return snapshot(false, 0);
    }

    /**
     * Flattens a snapshot into the handful of readings worth putting in a run report.
     *
     * <p>Deliberately small. The full snapshot belongs in a file or in front of a human; what a run
     * report needs is the verdict, whether the firing loop is alive, and what killed it.
     */
    public static Map<String, Object> summarise(JsonNode snapshot) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("verdict", snapshot.path("verdict").asText(""));

        JsonNode firingLoop = snapshot.path("firingLoop");
        summary.put("firingLoopState", firingLoop.path("state").asText("UNKNOWN"));
        summary.put("firingLoopAlive", firingLoop.path("alive").asBoolean(false));
        if (!firingLoop.path("failureType").isMissingNode() && !firingLoop.path("failureType").isNull()) {
            summary.put("firingLoopFailure", firingLoop.path("failureType").asText()
                    + ": " + firingLoop.path("failureMessage").asText(""));
        }

        JsonNode listener = snapshot.path("listenerFailures");
        if (listener.path("stateless").asLong(0L) > 0L) {
            summary.put("statelessListenerFailures", listener.path("stateless").asLong());
        }
        return summary;
    }

    /**
     * Counter deltas between two snapshots.
     *
     * <p>This is the reading the firing count cannot give you. Comparing before and after a workload
     * answers, in order: did the events reach the node at all, were they inserted into a session,
     * did they create matches, and were those matches fired. Whichever of those stops advancing is
     * where the pipeline broke.
     */
    public static Map<String, Object> counterDeltas(JsonNode before, JsonNode after) {
        Map<String, Object> deltas = new LinkedHashMap<>();
        JsonNode from = before.path("counters");
        JsonNode to = after.path("counters");

        to.fieldNames().forEachRemaining(name -> {
            long delta = to.path(name).asLong(0L) - from.path(name).asLong(0L);
            if (delta != 0L) {
                deltas.put(name, delta);
            }
        });
        return deltas;
    }

    public String describeTarget() {
        return target.diagnosticsBaseUrl();
    }

    /** A duration long enough to be worth reporting, used to keep timeouts honest in messages. */
    public Duration requestTimeout() {
        return target.requestTimeout();
    }
}
