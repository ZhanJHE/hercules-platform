package com.zhanjh.hercules.cache.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisCacheLoadLock 单元测试：验证分布式回源锁的抢占/释放/容错语义（阶段 A+ 防线③）。
 *
 * <p>测试策略：Mockito 桩 StringRedisTemplate（SET NX PX 与 Lua 释放脚本），
 * 不依赖真实 Redis。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>SET NX 抢占：首次 true、二次 false（互斥）；</li>
 *   <li>释放：Lua 脚本按令牌删除，释放后可再次抢占；</li>
 *   <li>容错约定：Redis 异常时 tryLock 返回 false（按「未抢到」处理，绝不抛出）。</li>
 * </ul>
 *
 * @author zhanjh
 * @since 0.0.1
 */
@ExtendWith(MockitoExtension.class)
class CacheLoadLockTest {

    @Mock
    private StringRedisTemplate template;

    @Mock
    private ValueOperations<String, String> valueOps;

    /**
     * 验证点：SET NX 抢占互斥——首次抢到，同键第二次抢不到。
     */
    @Test
    void tryLockIsMutuallyExclusive() {
        when(template.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true, false);

        CacheLoadLock lock = new RedisCacheLoadLock(template);
        assertThat(lock.tryLock("course:1", Duration.ofSeconds(3))).isTrue();
        assertThat(lock.tryLock("course:1", Duration.ofSeconds(3))).isFalse();
    }

    /**
     * 验证点：unlock 经 Lua 脚本按令牌删除；释放后同键可再次抢占。
     */
    @Test
    void unlockReleasesHeldLock() {
        when(template.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true, true);
        when(template.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(1L);

        CacheLoadLock lock = new RedisCacheLoadLock(template);
        assertThat(lock.tryLock("course:1", Duration.ofSeconds(3))).isTrue();
        lock.unlock("course:1");
        verify(template).execute(any(RedisScript.class), anyList(), anyString());

        // 释放后可再次抢占
        assertThat(lock.tryLock("course:1", Duration.ofSeconds(3))).isTrue();
    }

    /**
     * 验证点：Redis 异常时 tryLock 按「未抢到」处理返回 false，绝不向调用方抛异常。
     */
    @Test
    void redisFailureTreatedAsNotAcquired() {
        when(template.opsForValue()).thenThrow(new RuntimeException("redis down"));

        CacheLoadLock lock = new RedisCacheLoadLock(template);
        assertThat(lock.tryLock("course:1", Duration.ofSeconds(3))).isFalse();
    }
}
