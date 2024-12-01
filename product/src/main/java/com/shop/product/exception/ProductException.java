package com.shop.product.exception;

import jakarta.annotation.Nullable;

public class ProductException extends Exception {
    private final ProductError error;
    @Nullable
    private final String id;

    public ProductException(ProductError error, @Nullable String id) {
        this.error = error;
        this.id = id;
    }

    public ProductError getError() {
        return error;
    }

    @Nullable
    public String getId() {
        return id;
    }
}
