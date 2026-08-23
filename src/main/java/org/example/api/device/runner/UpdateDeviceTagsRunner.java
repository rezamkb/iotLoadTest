package org.example.api.device.runner;

import org.example.api.device.Config;
import org.example.api.device.CsvReader;
import org.example.api.device.DeviceClient;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class UpdateDeviceTagsRunner {
    private final Path devicesFile;
    private final String tagCode;
    private final DeviceClient client;
    private final ExecutorService executor;
    private final AtomicInteger succeeded = new AtomicInteger();
    private final List<UpdateFailure> failures =
            Collections.synchronizedList(new ArrayList<>());

    public UpdateDeviceTagsRunner(String devicesFile, String tagCode) {
        this.devicesFile = Path.of(devicesFile);
        this.tagCode = tagCode;
        this.client = new DeviceClient();
        this.executor = Executors.newFixedThreadPool(Config.THREAD_POOL_SIZE);
    }

    public void run() {
        try {
            List<String> deviceIds = CsvReader.readDeviceIds(devicesFile);
            List<CompletableFuture<Void>> tasks = deviceIds.stream()
                    .map(deviceId -> CompletableFuture.runAsync(
                            () -> updateTags(deviceId), executor))
                    .toList();

            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
            printSummary(deviceIds.size());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not update device tags from " + devicesFile + ": " + e.getMessage(), e);
        } finally {
            executor.shutdown();
        }
    }

    private void updateTags(String deviceId) {
        try {
            HttpResponse<String> response = client.updateTags(deviceId, tagCode);
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                succeeded.incrementAndGet();
                System.out.printf("Updated tags for deviceId=%s, HTTP %d -> OK%n",
                        deviceId, response.statusCode());
                return;
            }

            recordFailure(deviceId, "HTTP " + response.statusCode(), response.body());
        } catch (Exception e) {
            recordFailure(deviceId, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private void recordFailure(String deviceId, String reason, String details) {
        UpdateFailure failure = new UpdateFailure(deviceId, reason, details);
        failures.add(failure);
        System.err.printf("Failed deviceId=%s, reason=%s, response=%s%n",
                failure.deviceId(), failure.reason(), failure.details());
    }

    private void printSummary(int total) {
        System.out.printf("Tag update finished: total=%d, succeeded=%d, failed=%d%n",
                total, succeeded.get(), failures.size());
        if (!failures.isEmpty()) {
            System.err.println("Failed device IDs:");
            failures.forEach(failure -> System.err.printf("  %s -> %s: %s%n",
                    failure.deviceId(), failure.reason(), failure.details()));
        }
    }

    private record UpdateFailure(String deviceId, String reason, String details) {
    }
}
