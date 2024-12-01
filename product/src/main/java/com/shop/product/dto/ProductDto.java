package com.shop.product.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.shop.product.model.Product;

public record ProductDto(
        String id,
        String name,
        // Does not allow null url to be included in the API when this field is being added
        // Some items in the NoSQL database do not have this added field
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String url,
        String code,
        String model,
        float price
) {
    public ProductDto(Product product) {
        this(
            product.getId(),
            product.getProductName(),
            product.getProductUrl(),
            product.getCode(),
            product.getModel(),
            product.getPrice()
        );
    }

    static public Product toProduct(ProductDto dto) {
        var product = new Product();
        product.setId(dto.id());
        product.setProductName(dto.name());
        product.setProductUrl(dto.url());
        product.setCode(dto.code());
        product.setModel(dto.model());
        product.setPrice(dto.price());
        return product;
    }
}
