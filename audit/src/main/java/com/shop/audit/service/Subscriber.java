package com.shop.audit.service;

import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.entities.Segment;
import com.amazonaws.xray.entities.TraceID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.audit.dto.EventType;
import com.shop.audit.dto.ProductEventDto;
import com.shop.audit.dto.SnsEventDto;
import com.shop.audit.repository.ProductEventRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class Subscriber {
    private static final Logger LOG = LogManager.getLogger(Subscriber.class);
    private final ObjectMapper mapper;
    private final SqsAsyncClient sqsClient;
    private final String sqsUrl;
    private final ReceiveMessageRequest request;
    private final ProductEventRepository repository;

    public Subscriber(
            @Value("${aws.sqs.product.url}") String sqsUrl,
            ObjectMapper mapper,
            SqsAsyncClient sqsClient,
            ProductEventRepository repository
    ) {
        this.mapper = mapper;
        this.sqsClient = sqsClient;
        this.sqsUrl = sqsUrl;

        this.request = ReceiveMessageRequest.builder()
                .maxNumberOfMessages(5)
                .queueUrl(sqsUrl)
                .build();
        this.repository = repository;
    }

    @Scheduled(fixedDelay = 1000)
    public void subscribe() {
        List<Message> events;
        while (!(events = this.poll()).isEmpty()) {
            LOG.info("sqs - subscription - {} events", events.size());
            events.parallelStream().forEach(event -> {
                SnsEventDto snsEvent;
                try {
                    snsEvent = this.mapper.readValue(event.body(), SnsEventDto.class);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }

                var segment = createXraySegment(snsEvent);
                segment.run(() -> {
                    try {
                        putTracingInfo(snsEvent);
                        CompletableFuture.allOf(this.processByType(snsEvent), this.delete(event)).join();
                        LOG.info("sqs - subscription - event deleted");
                    } catch (Exception ex) {
                        LOG.error("sqs - subscription - event parse error");
                        throw new RuntimeException(ex);
                    } finally {
                        clearTracingInfo();
                        closeSegment(segment);
                    }
                }, AWSXRay.getGlobalRecorder()); // Records the segment
            });
        }
        AWSXRay.endSegment();
    }

    private static void closeSegment(Segment segment) {
        var endedAt = Instant.now().getEpochSecond();
        segment.setEndTime(endedAt);
        segment.end();
        segment.close();
    }

    private static Segment createXraySegment(SnsEventDto snsEvent) {
        var startAt = Instant.now().getEpochSecond();
        // This trace id needs to be propagated from product to sqs
        // Adds a new segment to the original trace created by product
        var traceId = TraceID.fromString(snsEvent.attributes().traceId().value());
        var segment = AWSXRay.beginSegment("product-events-sqs-subscription");
        segment.setOrigin("AWS::ECS::Container");
        segment.setStartTime(startAt);
        segment.setTraceId(traceId);
        return segment;
    }

    private static void clearTracingInfo() {
        ThreadContext.clearAll();
    }

    private CompletableFuture<Void> processByType(SnsEventDto snsEvent) throws Exception {
        LOG.info("sqs - subscription - start processing");
        var value = snsEvent.attributes().eventType().value();
        var type = EventType.valueOf(value);
        LOG.info("sqs - subscription - event type: {}", value);
        switch (type) {
            case PRODUCT_CREATED, PRODUCT_UPDATED, PRODUCT_DELETED -> {
                LOG.info("sqs - subscription - process event: {}", value);
                var event = this.mapper.readValue(snsEvent.message(), ProductEventDto.class);
                LOG.info("sqs - subscription - event: {} - product: {}", value, event.id());
                return this.save(snsEvent, event, type);
            }
            default -> {
                LOG.error("sqs - subscription error - event: {}", type);
                throw new Exception("sqs - subscription error - event");
            }
        }
    }

    private CompletableFuture<Void> save(SnsEventDto snsEvent, ProductEventDto productEvent, EventType eventType) {
        return this.repository.save(
                productEvent,
                eventType,
                snsEvent.eventId(),
                snsEvent.attributes().requestId().value(),
                snsEvent.attributes().traceId().value());
    }

    private static void putTracingInfo(SnsEventDto snsEvent) {
        ThreadContext.put("eventId", snsEvent.eventId());
        ThreadContext.put("requestId", snsEvent.attributes().requestId().value());
    }

    private List<Message> poll() {
        return this.sqsClient.receiveMessage(this.request).join().messages();
    }

    private CompletableFuture<DeleteMessageResponse> delete(Message event) {
        LOG.info("sqs - subscription - deleting event");
        var request = DeleteMessageRequest.builder()
                .queueUrl(sqsUrl)
                .receiptHandle(event.receiptHandle())
                .build();
        return this.sqsClient.deleteMessage(request);
    }
}
