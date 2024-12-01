package com.shop.audit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Ignores any property that is null
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductEventDto(
        String id,
        String code,
        float price,
        String email
) {
}
