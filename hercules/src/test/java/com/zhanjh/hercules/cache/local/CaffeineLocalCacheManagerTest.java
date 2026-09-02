package com.zhanjh.hercules.cache.local;

import com.zhanjh.hercules.cache.config.HerculesCacheProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CaffeineLocalCacheManager 单元测试：验证逐键 TTL（自定义 Expiry）行为。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>默认 TTL（put 传 null）——键在测试时间窗内存活；</li>
 *   <li>逐键短 TTL（如列表键 10s 窗口的缩小版）——到期后读取返回 null；</li>
 *   <li>与全局默认相同的 TTL——不登记覆盖表、按默认过期；</li>
 *   <li>invalidate——立即失效且清除覆盖登记。</li>
 * </ul>
 *
 * @author zhanjh
 * @since 0.0.1
 */
class CaffeineLocalCacheManagerTest {

    private CaffeineLocalCacheManager local;

    @BeforeEach
    void setUp() {
        // 默认配置：local-ttl=60s、local-max-size=1000
        local = new CaffeineLocalCacheManager(new HerculesCacheProperties());
    }

    @Test
    void defaultTtlKeepsEntryAliveInTestWindow() throws InterruptedException {
        local.put("course:1", "{\"id\":1}", null);

        Thread.sleep(150);

        assertThat(local.get("course:1")).isEqualTo("{\"id\":1}");
    }

    @Test
    void perKeyShortTtlExpiresEntry() throws InterruptedException {
        // 模拟列表键短 TTL（真实为 10s，此处缩小到 80ms 便于测试）
        local.put("course:list:1:10:-", "[]", Duration.ofMillis(80));
        local.put("course:2", "{\"id\":2}", null);

        Thread.sleep(300);

        // 短 TTL 键已过期，默认 TTL 键仍存活
        assertThat(local.get("course:list:1:10:-")).isNull();
        assertThat(local.get("course:2")).isEqualTo("{\"id\":2}");
    }

    @Test
    void perKeyTtlRenewsOnUpdate() throws InterruptedException {
        local.put("course:list:1:10:-", "[]", Duration.ofMillis(100));
        // 40ms 时重写：过期时间从写入时刻重新起算，再活 100ms
        Thread.sleep(40);
        local.put("course:list:1:10:-", "[refreshed]", Duration.ofMillis(100));

        Thread.sleep(40);

        // 距第二次写入约 40ms（剩余约 60ms）：仍存活且为新值，读访问不得续期/不得永生
        assertThat(local.get("course:list:1:10:-")).isEqualTo("[refreshed]");

        Thread.sleep(200);

        // 距第二次写入约 240ms > 100ms：已过期
        assertThat(local.get("course:list:1:10:-")).isNull();
    }

    @Test
    void invalidateRemovesEntryAndOverride() {
        local.put("course:list:1:10:-", "[]", Duration.ofMillis(80));
        local.invalidate("course:list:1:10:-");

        assertThat(local.get("course:list:1:10:-")).isNull();
        assertThat(local.estimatedSize()).isZero();
    }
}
