package com.shop.product.exception;

import org.springframework.http.HttpStatus;

public enum ProductError {
    PRODUCT_NOT_FOUND("Product not found", HttpStatus.NOT_FOUND),
    PRODUCT_CODE_CONFLICT("Product code conflict", HttpStatus.CONFLICT);

    private final String message;
    private final HttpStatus status;

    ProductError(String message, HttpStatus status) {
        this.message = message;
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
