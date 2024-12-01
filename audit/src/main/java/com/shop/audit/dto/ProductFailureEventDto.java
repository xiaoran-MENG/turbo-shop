package com.shop.audit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductFailureEventDto(
        int status,
        String error,
        String email,
        String id
) {
}
