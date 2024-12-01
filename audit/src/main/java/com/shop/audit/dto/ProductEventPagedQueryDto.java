package com.shop.audit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record ProductEventPagedQueryDto(
        List<ProductEventQueryDto> items,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String evaluatedAt,
        int count
) {
}
