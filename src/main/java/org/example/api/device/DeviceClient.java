package org.example.api.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.api.device.dto.CreateDeviceReq;
import org.example.api.device.dto.CreateDeviceResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class DeviceClient {
    private final HttpClient client;
    private final ObjectMapper mapper;

    public DeviceClient() {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * Sends the creation request and parses only the fields in DeviceResponse.
     */
    public CreateDeviceResponse create(CreateDeviceReq device) throws Exception {
        String json = mapper.writeValueAsString(device);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(Config.BASE_URL))
                .timeout(Duration.ofSeconds(10))
                .header("Accept",        "*/*")
                .header("Content-Type",  "application/json")
                .header("Authorization", authorizationHeader())
                .header("Cookie",        "JSESSIONID=" + Config.JSESSIONID)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        // parse only needed props; ignores the rest
        return mapper.readValue(resp.body(), CreateDeviceResponse.class);
    }

    public HttpResponse<String> updateTags(String deviceId, String code) throws Exception {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId must not be blank");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("tag code must not be blank");
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.putObject("tags").put("code", code);
        String body = mapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Config.BASE_URL + "/" + deviceId + "/tags"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "*/*")
                .header("Content-Type", "text/plain")
                .header("Authorization", authorizationHeader())
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }


    public int attachDeviceToEdge(String DeviceId,String edgeId) throws Exception {
        String url = Config.URL+"/edges/"+edgeId+"/devices/"+DeviceId;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept",        "*/*")
                .header("Content-Type",  "application/json")
                .header("Authorization", authorizationHeader())
//                .header("Cookie",        "JSESSIONID=" + Config.JSESSIONID)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        System.out.println( "attachDeviceToEdge url is : " +resp.request().uri().toString() + "  with this response code : " + resp.statusCode() );
        // parse only needed props; ignores the rest
     //   if (resp.statusCode() != 200) {}
        return resp.statusCode();
    }

    private static String authorizationHeader() {
        String token = Config.AUTH_TOKEN.trim();
        return token.regionMatches(true, 0, "Bearer ", 0, 7)
                ? token
                : "Bearer " + token;
    }




}
