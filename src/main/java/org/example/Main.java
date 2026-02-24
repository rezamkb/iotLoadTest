package org.example;

import org.example.api.device.runner.AttachDeviceToEdgeRunner;
import org.example.api.device.runner.CreateDeviceLoadTestRunner;
import org.example.mqtt.edge.HPMqttEdgePublisher;

public class Main {
    public static void main(String[] args) {

      new CreateDeviceLoadTestRunner("devices5.csv").run();

   //  new AttachDeviceToEdgeRunner("devices1.csv","5tq9l78nso1").run();

//        try {
//
//            //dvcout/5tq9l78nso1/JOQHROH4WIA1TFK6AN33NZA/edge/twin/#
//            new HPMqttEdgePublisher("edgeData/edge_5tq9l78nso1.csv"
//                    ,"5tq9l78nso1"
//                    ,"JOQHROH4WIA1TFK6AN33NZA"
//                    ,"8NB6MEG574SC6B2MV65GGB8");
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        }
        System.out.println("All done!");

    }
}