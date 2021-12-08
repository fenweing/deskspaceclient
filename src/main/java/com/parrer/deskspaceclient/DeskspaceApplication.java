package com.parrer.deskspaceclient;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.parrer")
public class DeskspaceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeskspaceApplication.class, args);
    }

}
