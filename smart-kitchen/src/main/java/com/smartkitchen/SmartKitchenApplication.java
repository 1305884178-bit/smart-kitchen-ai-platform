package com.smartkitchen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication
@MapperScan("com.smartkitchen.mapper")
public class SmartKitchenApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartKitchenApplication.class, args);
    }
}
