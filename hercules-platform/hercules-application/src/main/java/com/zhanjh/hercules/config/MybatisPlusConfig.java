package com.zhanjh.hercules.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 全局配置：Mapper 扫描与分页插件。
 *
 * <p>@MapperScan 将 com.zhanjh.hercules.mapper 包下全部接口
 * （CourseMapper / EnrollmentMapper / CacheVersionMapper）注册为 Mapper Bean，
 * 免去在每个接口上单独标注 @Mapper。
 *
 * <p>分页能力由 MybatisPlusInterceptor + PaginationInnerInterceptor 提供：
 * 分页拦截器采用无参构造、不固定 DbType，方言在执行时按数据源连接自动识别，
 * 因此同一套配置可同时兼容生产 MySQL 与测试 H2，无需按 profile 切换。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Configuration
@MapperScan("com.zhanjh.hercules.mapper")
public class MybatisPlusConfig {

    /**
     * MyBatis-Plus 拦截器链：目前仅挂载分页内嵌拦截器，支撑 CourseService 的 selectPage。
     *
     * <p>PaginationInnerInterceptor 使用无参构造、不写死 DbType：
     * 方言按当前数据源连接自动识别，同一套配置兼容 MySQL（生产）与 H2（测试）。
     *
     * @return 已装配分页插件的 MybatisPlusInterceptor
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        return interceptor;
    }
}
