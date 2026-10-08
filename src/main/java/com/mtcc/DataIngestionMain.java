package com.mtcc;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@RequiredArgsConstructor
public class DataIngestionMain {

    public static void main(final String[] args) {
        SpringApplication.run(DataIngestionMain.class, args);
    }
}
