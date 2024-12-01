package com.shop.audit.repository;

import com.amazonaws.xray.spring.aop.XRayEnabled;
import com.shop.audit.dto.EventType;
import com.shop.audit.dto.ProductEventDto;
import com.shop.audit.model.ProductEvent;
import com.shop.audit.model.ProductInfoEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Repository
@XRayEnabled
public class ProductEventRepository {
    private static final Logger LOG = LogManager.getLogger(ProductEventRepository.class);
    private final DynamoDbEnhancedAsyncClient client;
    private final DynamoDbAsyncTable<ProductEvent> table;

    public ProductEventRepository(
            @Value("${aws.events.table}") String tableName,
            DynamoDbEnhancedAsyncClient client) {
        this.client = client;
        this.table = this.client.table(tableName, TableSchema.fromBean(ProductEvent.class));
    }

    // products/events?eventType=PRODUCT_UPDATED&take=2&exclusiveStartTimestamp=2
    public SdkPublisher<Page<ProductEvent>> getPageByType(String eventType, String startedAtExclusive, int take) {
        var pk = "#product_".concat(eventType);
        var key = Key.builder().partitionValue(pk).build();
        var condition = QueryConditional.keyEqualTo(key);
        var startKey = this.createExclusiveStartKey(pk, startedAtExclusive);
        var request = QueryEnhancedRequest.builder()
                .queryConditional(condition)
                .exclusiveStartKey(startKey)
                .limit(take)
                .build();
        var pagesCount = 1;
        return this.table.query(request).limit(pagesCount);
    }

    // products/events?eventType=PRODUCT_UPDATED&from=1&to=4&take=2&exclusiveStartTimestamp=2
    public SdkPublisher<Page<ProductEvent>> getPageByTypeAndRange(String eventType, String startedAtExclusive, String from, String to, int take) {
        var pk = "#product_".concat(eventType);
        var fromKey = Key.builder().partitionValue(pk).sortValue(from).build();
        var toKey = Key.builder().partitionValue(pk).sortValue(to).build();
        var condition = QueryConditional.sortBetween(fromKey, toKey);
        var startKey = this.createExclusiveStartKey(pk, startedAtExclusive);
        var request = QueryEnhancedRequest.builder()
                .queryConditional(condition)
                .exclusiveStartKey(startKey)
                .limit(take)
                .build();
        var pagesCount = 1;
        return this.table.query(request).limit(pagesCount);
    }

    public CompletableFuture<Void> save(
            ProductEventDto dto,
            EventType eventType,
            String eventId,
            String requestId,
            String traceId) {
        var timestamp = Instant.now().toEpochMilli();
        var ttl = Instant.now().plusSeconds(300).getEpochSecond(); // 5m
        var event = createProductEvent(dto, eventType, timestamp, ttl, eventId, requestId, traceId);
        return this.table.putItem(event);
    }

    private static ProductEvent createProductEvent(
            ProductEventDto dto,
            EventType eventType,
            long timestamp,
            long ttl,
            String eventId,
            String requestId,
            String traceId) {
        var event = new ProductEvent();
        event.setPk("#product_".concat(eventType.name())); // #product_PRODUCT_CREATED
        event.setSk(String.valueOf(timestamp));
        event.setCreatedAt(timestamp);
        event.setTtl(ttl);
        event.setEmail(dto.email());
        event.setInfo(createProductEventInfo(dto, eventId, requestId, traceId));
        return event;
    }

    private static ProductInfoEvent createProductEventInfo(
            ProductEventDto dto,
            String eventId,
            String requestId,
            String traceId) {
        var info = new ProductInfoEvent();
        info.setCode(dto.code());
        info.setId(dto.id());
        info.setPrice(dto.price());
        info.setEventId(eventId);
        info.setRequestId(requestId);
        info.setTraceId(traceId);
        return info;
    }

    private Map<String, AttributeValue> createExclusiveStartKey(String pk, String startedAtExclusive) {
        return startedAtExclusive == null ? null :
            Map.of(
            "pk", AttributeValue.builder().s(pk).build(),
            "sk", AttributeValue.builder().s(startedAtExclusive).build());
    }
}
