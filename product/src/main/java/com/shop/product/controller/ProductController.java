package com.shop.product.controller;

import com.amazonaws.xray.spring.aop.XRayEnabled;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.shop.product.dto.EventType;
import com.shop.product.dto.ProductDto;
import com.shop.product.exception.ProductError;
import com.shop.product.exception.ProductException;
import com.shop.product.repository.ProductRepository;
import com.shop.product.service.SnsPublisher;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/products")
@XRayEnabled // Inspects how much time it takes to execute an operation
public class ProductController {
    private static final Logger LOG = LogManager.getLogger(ProductController.class);
    private final ProductRepository repository;
    private final SnsPublisher publisher;

    public ProductController(ProductRepository repository, SnsPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    @GetMapping
    public ResponseEntity<?> get(@RequestParam(required = false) String code) throws ProductException {
        LOG.info("GET /products");
        if (code == null) {
            var results = new ArrayList<ProductDto>();
            this.repository
                    .get()
                    .items()
                    .subscribe(p -> results.add(new ProductDto(p)))
                    .join();
            return new ResponseEntity<>(results, HttpStatus.OK);
        }

        LOG.info("GET /products?code={}", code);
        var result = this.repository.getByCode(code).join();
        if (result == null) throw new ProductException(ProductError.PRODUCT_NOT_FOUND, null);
        return new ResponseEntity<>(new ProductDto(result), HttpStatus.OK);
    }

    @GetMapping("{id}")
    public ResponseEntity<ProductDto> getById(@PathVariable("id") String id) throws ProductException {
        LOG.info("GET /products/{}", id);
        var product = this.repository.getById(id).join();
        if (product == null) throw new ProductException(ProductError.PRODUCT_NOT_FOUND, id);
        return new ResponseEntity<>(new ProductDto(product), HttpStatus.OK);
    }

    @PostMapping
    public ResponseEntity<ProductDto> post(@RequestBody ProductDto dto) throws ProductException, JsonProcessingException, ExecutionException, InterruptedException {
        LOG.info("POST /products/");
        var product = ProductDto.toProduct(dto);
        product.setId(UUID.randomUUID().toString());
        // Parallel - create product in DB and publish created event
        var creating = this.repository.post(product);
        var publishing = this.publisher.publish(product, EventType.PRODUCT_CREATED, "xrmeng720@gmail.com");
        CompletableFuture.allOf(creating, publishing).join();
        var response = publishing.get(); // To call after join
        ThreadContext.put("eventId", response.messageId());
        LOG.info("POST - OK - {}", product.getId());
        return new ResponseEntity<>(new ProductDto(product), HttpStatus.CREATED);
    }

    @PutMapping("{id}")
    public ResponseEntity<ProductDto> put(
            @RequestBody ProductDto dto,
            @PathVariable("id") String id) throws ProductException, JsonProcessingException {
        try {
            LOG.info("PUT /products/{}", id);
            var product = ProductDto.toProduct(dto);
            var result = this.repository.put(product, id).join();
            var response = this.publisher.publish(product, EventType.PRODUCT_UPDATED, "xrmeng720@gmail.com").join();
            ThreadContext.put("eventId", response.messageId());
            LOG.info("PUT - OK - {}", result.getId());
            return new ResponseEntity<>(new ProductDto(result), HttpStatus.OK);
        } catch (CompletionException e) {
            throw new ProductException(ProductError.PRODUCT_NOT_FOUND, id);
        }
    }

    @DeleteMapping("{id}")
    public ResponseEntity<ProductDto> delete(@PathVariable("id") String id) throws ProductException, JsonProcessingException {
        var product = this.repository.delete(id).join();
        if (product == null) throw new ProductException(ProductError.PRODUCT_NOT_FOUND, id);
        var response = this.publisher.publish(product, EventType.PRODUCT_DELETED, "xrmeng720@gmail.com").join();
        ThreadContext.put("eventId", response.messageId()); // The logs generated after this can contain the event id for tracing across services
        LOG.info("DELETE /products/{}", id);
        return new ResponseEntity<>(new ProductDto(product), HttpStatus.OK);
    }
}
