package com.shop.invoice.config;

import com.amazonaws.xray.interceptors.TracingInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

@Configuration
public class DynamoDbConfig {
    @Value("${aws.region}")
    private String region;

    @Bean
    public DynamoDbEnhancedAsyncClient dynamoDbEnhancedAsyncClient() {
        return DynamoDbEnhancedAsyncClient.builder()
                .dynamoDbClient(this.dynamoDbAsyncClient())
                .build();
    }

    @Bean
    public DynamoDbAsyncClient dynamoDbAsyncClient() {
        var xray = new TracingInterceptor();
        var config = ClientOverrideConfiguration.builder()
                .addExecutionInterceptor(xray) // Xray tracing
                .build();
        return DynamoDbAsyncClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(Region.of(region))
                .overrideConfiguration(config)
                .build();
    }
}

