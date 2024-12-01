package com.shop.audit.repository;

import com.shop.audit.dto.EventType;
import com.shop.audit.dto.ProductFailureEventDto;
import com.shop.audit.model.ProductFailureEvent;
import com.shop.audit.model.ProductInfoFailureEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

@Repository
public class ProductFailureEventRepository {
    private static final Logger LOG = LogManager.getLogger(ProductFailureEventRepository.class);
    private final DynamoDbEnhancedAsyncClient client;
    private final DynamoDbAsyncTable<ProductFailureEvent> table;

    public ProductFailureEventRepository(
            @Value("${aws.events.table}") String tableName,
            DynamoDbEnhancedAsyncClient client
    ) {
        this.client = client;
        this.table = this.client.table(tableName, TableSchema.fromBean(ProductFailureEvent.class));
    }

    public CompletableFuture<Void> save(
            ProductFailureEventDto dto,
            EventType eventType,
            String eventId,
            String requestId,
            String traceId)
    {
        var createdAt = Instant.now().toEpochMilli();
        var ttl = Instant.now().plusSeconds(300).getEpochSecond(); // 5m
        var event = createProductEvent(dto, eventType, createdAt, ttl, eventId, requestId, traceId);
        return this.table.putItem(event);
    }

    private static ProductFailureEvent createProductEvent(
            ProductFailureEventDto dto,
            EventType eventType,
            long createdAt,
            long ttl,
            String eventId,
            String requestId,
            String traceId
    ) {
        var event = new ProductFailureEvent();
        event.setPk("#product_".concat(eventType.name())); // #product_PRODUCT_CREATED
        event.setSk(String.valueOf(createdAt));
        event.setCreatedAt(createdAt);
        event.setTtl(ttl);
        event.setEmail(dto.email());
        event.setInfo(createProductEventInfo(dto, eventId, requestId, traceId));
        return event;
    }

    private static ProductInfoFailureEvent createProductEventInfo(
            ProductFailureEventDto dto,
            String eventId,
            String requestId,
            String traceId
    ) {
        var info = new ProductInfoFailureEvent();
        info.setId(dto.id());
        info.setStatus(dto.status());
        info.setError(dto.error());
        info.setEventId(eventId);
        info.setRequestId(requestId);
        info.setTraceId(traceId);
        return info;
    }
}
