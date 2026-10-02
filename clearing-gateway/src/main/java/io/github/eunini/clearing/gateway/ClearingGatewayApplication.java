package io.github.eunini.clearing.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ClearingGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClearingGatewayApplication.class, args);
    }
}
