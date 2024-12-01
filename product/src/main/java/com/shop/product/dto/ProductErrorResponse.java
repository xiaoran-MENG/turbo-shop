package com.shop.product.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ProductErrorResponse(
        String message,
        int statusCode,
        String requestId,
        // productIs is nullable
        @JsonInclude(JsonInclude.Include.NON_NULL) String productId
) {
}
