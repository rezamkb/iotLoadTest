package org.example;

import org.example.api.device.CreateDeviceLoadTestRunner;

public class Main {
    public static void main(String[] args) {

        new CreateDeviceLoadTestRunner().run();
        System.out.println("All done!");

    }
}