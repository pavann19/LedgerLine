package com.ledgerline.projection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication
@EnableKafka
public class ProjectionApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProjectionApplication.class, args);
    }

    @Bean
    public tools.jackson.databind.json.JsonMapper objectMapper() {
        return tools.jackson.databind.json.JsonMapper.builder()
            .disable(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(tools.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    }
}
