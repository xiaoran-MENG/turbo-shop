package com.shop.product.service;

import com.amazonaws.xray.AWSXRay;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.product.dto.EventType;
import com.shop.product.dto.ProductEventDto;
import com.shop.product.dto.ProductFailureEventDto;
import com.shop.product.model.Product;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.sns.model.Topic;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

// Uses the SNS client bean to publish events
// To be injected in the controllers
@Service
public class SnsPublisher {
    private final ObjectMapper mapper;
    private final SnsAsyncClient client;
    private final Topic topic;

    public SnsPublisher(
            ObjectMapper mapper, // Converts Java object to JSON
            SnsAsyncClient client,
            @Qualifier("productSnsTopic") Topic topic) {
        this.mapper = mapper;
        this.client = client;
        this.topic = topic;
    }

    // Controller can await this operation
    public CompletableFuture<PublishResponse> publish(Product product, EventType eventType, String email) throws JsonProcessingException {
        var dto = new ProductEventDto(
                product.getId(),
                product.getCode(),
                product.getPrice(),
                email);
        var jsonStr = this.mapper.writeValueAsString(dto);
        return this.publish(jsonStr, eventType);
    }

    public CompletableFuture<PublishResponse> publishFailure(ProductFailureEventDto dto) throws JsonProcessingException {
        var jsonStr = this.mapper.writeValueAsString(dto);
        return this.publish(jsonStr, EventType.PRODUCT_FAILURE);
    }

    // Async client allows for parallelization of the returned completable future
    private CompletableFuture<PublishResponse> publish(String payload, EventType type) {
        return this.client.publish(PublishRequest.builder()
                        .messageAttributes(Map.of(
                                "eventType", MessageAttributeValue.builder()
                                        .dataType("String")
                                        .stringValue(type.name())
                                        .build(),
                                // Only need to use request id to trace the logs generated
                                // by all the services that have to do with this request
                                "requestId", MessageAttributeValue.builder()
                                        .dataType("String")
                                        .stringValue(ThreadContext.get("requestId"))
                                        .build(),
                                // XRay trace id
                                "traceId", MessageAttributeValue.builder()
                                        .dataType("String")
                                        .stringValue(
                                                Objects.requireNonNull(AWSXRay.getCurrentSegment())
                                                    .getTraceId()
                                                    .toString())
                                        .build()))
                        .message(payload)
                        .topicArn(this.topic.topicArn())
                .build());
    }
}
