package com.househunt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HouseHuntApplication {
    public static void main(String[] args) {
        SpringApplication.run(HouseHuntApplication.class, args);
    }
}
