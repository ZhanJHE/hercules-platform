package com.zhanjh.hercules;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 启动类：智慧校园多智能体服务平台（Hercules）MVP——分布式缓存一致性治理。
 *
 * <p>@SpringBootApplication 组合了自动配置与组件扫描（扫描 com.zhanjh.hercules 及其子包），
 * 启动时装配多级缓存（L1 Caffeine → L2 Redis）、向量时钟同步链、REST 接口等全部 Bean；
 * 内嵌 Servlet 容器默认监听 8080 端口（server.port）。
 *
 * <p>线程安全性：main 为单次入口调用，无共享状态。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@SpringBootApplication
public class HerculesApplication {

	/**
	 * 应用入口：委托 SpringApplication 完成上下文构建与内嵌容器启动。
	 *
	 * @param args 命令行参数（Spring Boot 标准 args，可覆盖配置属性）
	 */
	public static void main(String[] args) {
		SpringApplication.run(HerculesApplication.class, args);
	}

}
