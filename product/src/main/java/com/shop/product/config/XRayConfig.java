package com.shop.product.config;

import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.AWSXRayRecorderBuilder;
import com.amazonaws.xray.jakarta.servlet.AWSXRayServletFilter;
import com.amazonaws.xray.strategy.sampling.CentralizedSamplingStrategy;
import jakarta.servlet.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ResourceUtils;

import java.io.FileNotFoundException;

@Configuration
public class XRayConfig {
    private static final Logger LOG = LoggerFactory.getLogger(XRayConfig.class);

    public XRayConfig() {
        try {
            var configFle = ResourceUtils.getURL("classpath:xray/xray-sampling-rules.json");
            var recorder = AWSXRayRecorderBuilder
                    .standard()
                    .withDefaultPlugins()
                    .withSamplingStrategy(new CentralizedSamplingStrategy(configFle))
                    .build();
            AWSXRay.setGlobalRecorder(recorder);
        } catch (FileNotFoundException e) {
            LOG.error("XRay config file not found");
        }
    }

    @Bean
    public Filter tracingFilter() {
        return new AWSXRayServletFilter("product-segment");
    }
}
