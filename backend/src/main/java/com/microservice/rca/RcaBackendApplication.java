package com.microservice.rca;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RcaBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(RcaBackendApplication.class, args);
    }
}
