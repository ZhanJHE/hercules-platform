package com.zhanjh.hercules.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhanjh.hercules.model.AgentTrace;

/**
 * 智能体调用记录表 Mapper：仅继承 MyBatis-Plus BaseMapper（insert 由 BaseAgent 模板调用）。
 *
 * <p>MyBatis 动态代理生成实现，单例无状态，线程安全。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public interface AgentTraceMapper extends BaseMapper<AgentTrace> {
}
