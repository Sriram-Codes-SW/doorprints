package app.doorprints.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DoorprintsApplication {
    public static void main(String[] args) {
        SpringApplication.run(DoorprintsApplication.class, args);
    }
}
