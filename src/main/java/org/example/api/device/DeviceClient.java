package org.example.api.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.api.device.dto.CreateDeviceReq;
import org.example.api.device.dto.CreateDeviceResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
                .header("Authorization", "Bearer " + Config.AUTH_TOKEN)
                .header("Cookie",        "JSESSIONID=" + Config.JSESSIONID)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        // parse only needed props; ignores the rest
        return mapper.readValue(resp.body(), CreateDeviceResponse.class);
    }


    public int attachDeviceToEdge(String DeviceId,String edgeId) throws Exception {
        String url = Config.URL+"/edges/"+edgeId+"/devices/"+DeviceId;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept",        "*/*")
                .header("Content-Type",  "application/json")
                .header("Authorization", "Bearer " + Config.AUTH_TOKEN)
//                .header("Cookie",        "JSESSIONID=" + Config.JSESSIONID)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        System.out.println( "attachDeviceToEdge url is : " +resp.request().uri().toString() + "  with this response code : " + resp.statusCode() );
        // parse only needed props; ignores the rest
     //   if (resp.statusCode() != 200) {}
        return resp.statusCode();
    }




}
