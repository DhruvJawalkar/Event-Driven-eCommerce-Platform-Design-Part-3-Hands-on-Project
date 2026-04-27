package com.ecommerce.orderservice.config;

import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin.NewTopics;

@Configuration
public class KafkaTopicsConfig {

    @Bean
    public NewTopics allTopics() {
        return new NewTopics(
                TopicBuilder.name("orders")
                        .partitions(12)
                        .replicas(3)
                        .config(TopicConfig.RETENTION_MS_CONFIG, "604800000")
                        .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2")
                        .build(),

                TopicBuilder.name("payments")
                        .partitions(12)
                        .replicas(3)
                        .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT)
                        .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2")
                        .build(),

                TopicBuilder.name("inventory-events")
                        .partitions(6)
                        .replicas(3)
                        .config(TopicConfig.RETENTION_MS_CONFIG, "2592000000")
                        .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2")
                        .build()
        );
    }
}
