package com.shop.audit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SqsEventAttribute(
        @JsonProperty("Type")
        String type,
        @JsonProperty("Value")
        String value
) {
}
