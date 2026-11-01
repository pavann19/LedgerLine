package com.ledgerline.projection.consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class ProjectionRecoveryConfig {
    @Bean
    DefaultErrorHandler projectionErrorHandler(ProjectionQuarantine quarantine) {
        // A generic batch exception retries the ENTIRE transactional batch. Never
        // report a failed index: no prefix of a rolled-back batch was projected.
        var handler = new DefaultErrorHandler(quarantine::park, new FixedBackOff(500L, 2L));
        handler.setCommitRecovered(true);
        return handler;
    }
}
