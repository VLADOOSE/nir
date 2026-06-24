package com.vladoose.nir.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcMarketConfig implements WebMvcConfigurer {

    private final MarketInterceptor marketInterceptor;

    public WebMvcMarketConfig(MarketInterceptor marketInterceptor) {
        this.marketInterceptor = marketInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(marketInterceptor).addPathPatterns("/api/**");
    }
}
