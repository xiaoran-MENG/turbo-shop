package com.shop.audit.dto;

import com.shop.audit.model.ProductEvent;

public record ProductEventQueryDto(
        String productId,
        String code,
        float price,
        String requestId,
        String email,
        long createdAt) {

    public ProductEventQueryDto(ProductEvent event) {
        this(
            event.getInfo().getId(),
            event.getInfo().getCode(),
            event.getInfo().getPrice(),
            event.getInfo().getRequestId(),
            event.getEmail(),
            event.getCreatedAt()
        );
    }
}
