package com.shop.audit.controller;

import com.amazonaws.xray.spring.aop.XRayEnabled;
import com.shop.audit.dto.ProductEventPagedQueryDto;
import com.shop.audit.dto.ProductEventQueryDto;
import com.shop.audit.model.ProductEvent;
import com.shop.audit.repository.ProductEventRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/products/events")
@XRayEnabled
public class ProductEventController {
    private final ProductEventRepository repository;

    public ProductEventController(ProductEventRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ResponseEntity<ProductEventPagedQueryDto> getPage(
            @RequestParam String eventType,
            @RequestParam(defaultValue = "5") int take,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String startedAtExclusive) {
        var publisher = this.query(eventType, take, from, to, startedAtExclusive);
        var evaluatedAt = new AtomicReference<String>();
        var results = new ArrayList<ProductEventQueryDto>();
        publisher.subscribe(page -> extract(page, results, evaluatedAt)).join();
        var pagedResults = new ProductEventPagedQueryDto(results, evaluatedAt.get(), results.size());
        return new ResponseEntity<>(pagedResults, HttpStatus.OK);
    }

    private SdkPublisher<Page<ProductEvent>> query(String eventType, int take, String from, String to, String startedAtExclusive) {
        return from != null && to != null
                ? this.repository.getPageByTypeAndRange(eventType, startedAtExclusive, from, to, take)
                : this.repository.getPageByType(eventType, startedAtExclusive, take);
    }

    private static void extract(Page<ProductEvent> page, ArrayList<ProductEventQueryDto> results, AtomicReference<String> evaluatedAt) {
        var events = page.items().stream().map(ProductEventQueryDto::new).toList();
        results.addAll(events);
        var notLastPage = page.lastEvaluatedKey() != null;
        if (notLastPage) {
            var sk = page.lastEvaluatedKey().get("sk").s();
            evaluatedAt.set(sk);
        }
    }
}
