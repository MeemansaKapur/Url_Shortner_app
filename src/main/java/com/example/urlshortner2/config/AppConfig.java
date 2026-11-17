package com.example.urlshortner2.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.urlshortner2.cache.CacheStore;
import com.example.urlshortner2.cache.CachedLinkResolver;
import com.example.urlshortner2.link.LinkService;
import com.example.urlshortner2.storage.LinkRepository;

import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods = false)
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    CachedLinkResolver cachedLinkResolver(LinkRepository repo, CacheStore cache, AppProperties props,
                                          Clock clock, MeterRegistry meters) {
        var c = props.cache();
        return new CachedLinkResolver(repo, cache, c.enabled(), c.maxTtl(), c.negativeTtl(), clock, meters);
    }

    @Bean
    LinkService linkService(LinkRepository repo, CachedLinkResolver resolver, AppProperties props, Clock clock) {
        return new LinkService(repo, resolver, props.codeLength(), clock);
    }
}
