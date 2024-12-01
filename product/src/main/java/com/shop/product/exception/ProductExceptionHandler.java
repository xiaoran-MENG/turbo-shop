package com.shop.product.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.shop.product.dto.ProductErrorResponse;
import com.shop.product.dto.ProductFailureEventDto;
import com.shop.product.service.SnsPublisher;
import org.apache.logging.log4j.ThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ProductExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ProductExceptionHandler.class);
    private final SnsPublisher publisher;

    public ProductExceptionHandler(SnsPublisher publisher) {
        this.publisher = publisher;
    }

    @ExceptionHandler(value = { ProductException.class })
    protected ResponseEntity<Object> handle(ProductException e, WebRequest request) throws JsonProcessingException {
        var response = new ProductErrorResponse(
                e.getError().getMessage(),
                e.getError().getStatus().value(),
                ThreadContext.get("requestId"),
                e.getId()
        );

        var failure = new ProductFailureEventDto(
                e.getError().getStatus().value(),
                e.getError().getMessage(),
              "xrmeng720@gmail.com",
                e.getId()
        );

        // Publish event before CloudWatch
        var result = this.publisher.publishFailure(failure).join();
        ThreadContext.put("eventId", result.messageId());

        LOG.error(e.getError().getMessage()); // This log will have eventId in contextMap

        return handleExceptionInternal(
                e,
                response,
                new HttpHeaders(),
                e.getError().getStatus(),
                request
        );
    }
}
