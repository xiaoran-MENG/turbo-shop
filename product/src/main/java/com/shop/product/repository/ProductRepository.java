package com.shop.product.repository;

import com.amazonaws.xray.spring.aop.XRayEnabled;
import com.shop.product.exception.ProductError;
import com.shop.product.exception.ProductException;
import com.shop.product.model.Product;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.enhanced.dynamodb.model.PagePublisher;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

@Repository
@XRayEnabled
public class ProductRepository {
    private static final Logger LOG = LogManager.getLogger(ProductRepository.class);

    private final DynamoDbEnhancedAsyncClient dynamoDbClient;
    private DynamoDbAsyncTable<Product> products; // To access the typed items in the table

    public ProductRepository(
            DynamoDbEnhancedAsyncClient dynamoDbClient,
            @Value("${aws.product.table.name}") String table) {
        this.dynamoDbClient = dynamoDbClient;
        this.products = this.dynamoDbClient.table(table, TableSchema.fromBean(Product.class));
    }

    public PagePublisher<Product> get() {
        return this.products.scan(); // Do not use in prod
    }

    public CompletableFuture<Product> getById(String id) {
        LOG.info("Product ID: {}", id);
       var key = Key.builder()
               .partitionValue(id)
               .build();
       return this.products.getItem(key);
    }

    public CompletableFuture<Void> post(Product product) throws ProductException {
        var result = this.getByCodeGSI(product.getCode()).join();
        if (result == null)
            return this.products.putItem(product);
        throw new ProductException(ProductError.PRODUCT_CODE_CONFLICT, result.getId());
    }

    public CompletableFuture<Product> put(Product product, String id) throws ProductException {
        product.setId(id);
        var result = this.getByCodeGSI(product.getCode()).join();
        if (result != null && !result.getId().equals(product.getId()))
            throw new ProductException(ProductError.PRODUCT_CODE_CONFLICT, result.getId());
        // Updates only when the incoming product has the passed in id
        // Avoids a query to check if the product exists in the database
        var condition = Expression.builder()
                .expression("attribute_exists(id)")
                .build();
        var request = UpdateItemEnhancedRequest.builder(Product.class)
                .item(product)
                .conditionExpression(condition)
                .build();
        return this.products.updateItem(request);
    }

    public CompletableFuture<Product> delete(String id) {
        var key = Key.builder()
                .partitionValue(id)
                .build();
        return this.products.deleteItem(key);
    }

    public CompletableFuture<Product> getByCode(String code) {
        var result = this.getByCodeGSI(code).join();
        if (result == null)
            return CompletableFuture.supplyAsync(() -> null);
        return this.getById(result.getId());
    }

    private CompletableFuture<Product> getByCodeGSI(String code) {
        var results = new ArrayList<Product>(); // 1 product
        this.products
                .index("IndexOnCode")
                .query(QueryEnhancedRequest.builder()
                        .limit(1) // Take 1 from the results
                        .queryConditional(QueryConditional.keyEqualTo(Key.builder()
                                .partitionValue(code)
                                .build()))
                        .build())
                .subscribe(page -> results.addAll(page.items()))
                .join();

        if (results.isEmpty())
            return CompletableFuture.supplyAsync(() -> null);
        return CompletableFuture.supplyAsync(results::getFirst);
    }
}
