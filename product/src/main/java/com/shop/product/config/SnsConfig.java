package com.shop.product.config;

import com.amazonaws.xray.interceptors.TracingInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.Topic;

// SNS client configurations
@Configuration
public class SnsConfig {
    @Value("${aws.region}")
    private String region;

    @Value("${aws.sns.topic.product.events}")
    private String productSnsTopicArn;

    @Bean
    public SnsAsyncClient snsClient() {
        return SnsAsyncClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.create()) // Published granted to the task role in the infra project
                .region(Region.of(this.region))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                    .addExecutionInterceptor(new TracingInterceptor()) // XRay interceptor
                    .build())
                .build();
    }

    // This does not create the topic
    // The topic is created in the infra project
    // This is a representation of the topic
    @Bean(name = "productSnsTopic") // Specify the name in case there are other topics
    public Topic productSnsTopic() {
        return Topic.builder()
                .topicArn(this.productSnsTopicArn)
                .build();
    }
}
