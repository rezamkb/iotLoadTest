package org.example.api.device.runner;

import org.example.api.device.CsvReader;
import org.example.api.device.DeviceClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class AttachDeviceToEdgeRunner {





    private static  Path CSV_FILE;

    private static  String EDGE_ID;

    // tune to the max number of in-flight HTTP calls your Edge API can take
    private static final int MAX_CONCURRENCY = 10;
    private static final long AWAIT_TIMEOUT   = 10;   // minutes

    private final DeviceClient client;
    private final ExecutorService pool;

    public AttachDeviceToEdgeRunner(String devices_path,String edgeId) {
        CSV_FILE = Path.of(devices_path);
        EDGE_ID = edgeId;
        this.client = new DeviceClient();
        this.pool   = Executors.newFixedThreadPool(1,
                r -> { Thread t = new Thread(r, "edge-attach-worker"); t.setDaemon(true); return t; });
    }



    public void run() {
        List<String> ids = null;
        try {
            ids = CsvReader.readDeviceIds(CSV_FILE);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // one CompletableFuture per device-id, executed on the custom pool
        List<CompletableFuture<Void>> futures =
                ids.stream()
                        .map(id -> CompletableFuture.runAsync(
                                        () -> {

                                            try {
                                                client.attachDeviceToEdge(id, EDGE_ID);
                                            } catch (Exception e) {
                                                throw new RuntimeException(e.getMessage());
                                            }

                                        }, pool)
                                .exceptionally(ex -> {          // swallow or log per-call failures
                                    System.err.printf("Failed id %s → %s%n", id, ex.getMessage());
                                    return null;
                                }))
                        .collect(Collectors.toList());

        // block until all done (or first thrown if you drop the exceptionally() handler)
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    /** Call when your service is shutting down */
    public void shutdown() throws InterruptedException {
        pool.shutdown();
        if (!pool.awaitTermination(AWAIT_TIMEOUT, TimeUnit.MINUTES)) {
            pool.shutdownNow();
        }
    }

}
