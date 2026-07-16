package com.nova.backend;

import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NovaBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(NovaBackendApplication.class, args);
    }
}
