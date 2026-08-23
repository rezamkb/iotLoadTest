package org.example.api.device.runner;

import org.example.api.device.Config;
import org.example.api.device.CsvExporter;
import org.example.api.device.DeviceClient;
import org.example.api.device.DeviceReqFactory;
import org.example.api.device.DeviceTypeCreationPlan;
import org.example.api.device.DeviceTypeCsvReader;
import org.example.api.device.dto.CreateDeviceReq;
import org.example.api.device.dto.CreateDeviceResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CreateDevicesByTypeLoadTestRunner {
    private final Path deviceTypesFile;
    private final String outputFile;
    private final DeviceClient client = new DeviceClient();
    private final ExecutorService executor =
            Executors.newFixedThreadPool(Config.THREAD_POOL_SIZE);
    private final List<CreateDeviceResponse> storedResponses =
            Collections.synchronizedList(new ArrayList<>());

    public CreateDevicesByTypeLoadTestRunner(String deviceTypesFile, String outputFile) {
        this.deviceTypesFile = Path.of(deviceTypesFile);
        this.outputFile = outputFile;
    }

    public void run() {
        try {
            List<DeviceTypeCreationPlan> plans = DeviceTypeCsvReader.read(deviceTypesFile);
            List<CompletableFuture<Void>> tasks = plans.stream()
                    .flatMap(plan -> createTasks(plan).stream())
                    .toList();

            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
            CsvExporter.exportCreateDeviceResponses(storedResponses, outputFile);
            System.out.printf("Exported %d created devices to %s%n", storedResponses.size(), outputFile);
        } catch (IOException e) {
            throw new IllegalStateException("Could not process device type CSV: " + e.getMessage(), e);
        } finally {
            executor.shutdown();
        }
    }

    private List<CompletableFuture<Void>> createTasks(DeviceTypeCreationPlan plan) {
        List<CompletableFuture<Void>> tasks = new ArrayList<>(plan.numberOfDevices());
        for (int i = 0; i < plan.numberOfDevices(); i++) {
            tasks.add(CompletableFuture.runAsync(() -> createDevice(plan), executor));
        }
        return tasks;
    }

    private void createDevice(DeviceTypeCreationPlan plan) {
        CreateDeviceReq device = DeviceReqFactory.createDeviceReq(
                plan.deviceTypeId(),
                plan.deviceTypeName());
        try {
            CreateDeviceResponse response = client.create(device);
            storedResponses.add(response);
            System.out.printf("Created ID=%s, name=%s, typeId=%s -> OK%n",
                    response.getId(), response.getName(), response.getDeviceTypeId());
        } catch (Exception e) {
            System.err.printf("Failed creation for %s -> %s%n", device.getName(), e.getMessage());
        }
    }
}
