package com.smartkitchen;

import com.smartkitchen.config.DotenvLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SmartKitchenApplication {

    public static void main(String[] args) {
        DotenvLoader.load();
        SpringApplication.run(SmartKitchenApplication.class, args);
    }
}
