package com.shop.product.dto;

public record ProductEventDto(
        String id,
        String code,
        float price,
        String email
) {
}
