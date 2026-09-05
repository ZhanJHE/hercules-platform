package com.zhanjh.hercules.testsupport;

import com.zhanjh.hercules.auth.AuthStorePort;
import com.zhanjh.hercules.cache.remote.DistributedCacheManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试桩装配：以 @Primary 内存桩替换「真实 Redis L2」与「Redis 认证存储」，
 * 使 H2 冒烟/认证流测试完全不依赖外部中间件。
 *
 * <p>使用方式：测试类上加 {@code @Import(TestStubsConfig.class)}。
 * 说明：RedisDistributedCacheManager/RedisAuthStore 的生产 Bean 仍会创建（依赖的
 * StringRedisTemplate 由自动配置提供，Lettuce 惰性建连不会真正连接），但 @Primary
 * 使注入点一律命中内存桩。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestStubsConfig {

    /**
     * 内存 L2 桩（@Primary 覆盖 Redis 实现）。
     *
     * @return 内存分布式缓存管理器
     */
    @Bean
    @Primary
    DistributedCacheManager inMemoryDistributedCacheManager() {
        return new InMemoryDistributedCacheManager();
    }

    /**
     * 内存认证存储桩（@Primary 覆盖 Redis 实现）。
     *
     * @return 内存认证存储
     */
    @Bean
    @Primary
    AuthStorePort inMemoryAuthStore() {
        return new InMemoryAuthStore();
    }
}
