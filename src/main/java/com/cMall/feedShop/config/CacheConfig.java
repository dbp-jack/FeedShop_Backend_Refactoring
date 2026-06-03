package com.cMall.feedShop.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@EnableCaching
public class CacheConfig {

    // [BEFORE] redis 프로파일 구분 없이 항상 인메모리 캐시 사용
    // @Bean
    // public CacheManager cacheManager() {
    //     return new ConcurrentMapCacheManager("availableEvents");
    // }

    // [Phase 2-A] redis 프로파일 미적용 시 인메모리 캐시 사용
    // redis 프로파일 활성화 시 application-redis.properties의 RedisCacheManager 자동 구성
    @Bean
    @Profile("!redis")
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager("availableEvents");
    }
} 