package org.lilradish.lite.migration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MigrationApplication {

    public static void main(String[] args) {
        // Flyway has already run by the time run() returns; exiting explicitly stops a stray
        // non-daemon thread from keeping the JVM alive afterwards.
        System.exit(SpringApplication.exit(SpringApplication.run(MigrationApplication.class, args)));
    }
}
