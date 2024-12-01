package com.shop.audit.dto;

public record SqsEventAttributes(
        SqsEventAttribute traceId,
        SqsEventAttribute eventType,
        SqsEventAttribute requestId
) {
}
