package com.opcua;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * OPC UA 核心采集层启动入口。
 */
@SpringBootApplication
public class OpcUaCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpcUaCoreApplication.class, args);
    }
}
