package com.mtcc.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class OpenApiConfig {

    private final ApplicationContext applicationContext;

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info().title("POracle").version(this.version()));
    }

    private String version() {
        return Try
                .of(() -> this.applicationContext.getBean(BuildProperties.class).getVersion())
                .onFailure(e -> log.info("Build info not available. Please run mvn compile"))
                .getOrElse("Version unknown");
    }
}
