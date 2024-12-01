package com.shop.product.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ProductFailureEventDto(
        int status,
        String error,
        String email,
        // This id can be null
        // Don't include it in the event if it is null
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String id
) {
}
