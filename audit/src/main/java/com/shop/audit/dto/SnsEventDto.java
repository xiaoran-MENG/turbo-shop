package com.shop.audit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SnsEventDto(
    @JsonProperty("MessageId")
    String eventId,
    @JsonProperty("Message")
    String message,
    @JsonProperty("Type")
    String type,
    @JsonProperty("TopicArn")
    String topicArn,
    @JsonProperty("Timestamp")
    String timestamp,
    @JsonProperty("MessageAttributes")
    SqsEventAttributes attributes
) {
}
