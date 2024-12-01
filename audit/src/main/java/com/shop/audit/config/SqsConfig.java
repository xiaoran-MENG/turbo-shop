package com.shop.audit.config;

import com.amazonaws.xray.interceptors.TracingInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

@Component
public class SqsConfig {
    @Value("${aws.region}")
    private String region;

    @Bean
    public SqsAsyncClient sqsClient() {
        var config = ClientOverrideConfiguration.builder()
                .addExecutionInterceptor(new TracingInterceptor()) // Xray tracing
                .build();
        return SqsAsyncClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(Region.of(region))
                .overrideConfiguration(config)
                .build();
    }
}
