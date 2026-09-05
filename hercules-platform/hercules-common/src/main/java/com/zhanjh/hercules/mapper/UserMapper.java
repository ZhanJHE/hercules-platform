package com.zhanjh.hercules.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhanjh.hercules.model.User;

/**
 * 认证用户表访问接口（t_user）。
 *
 * <p>使用方：AuthUserSeeder（幂等播种）、AuthController（登录/刷新时查用户）。
 * 查询均为 username/userId 精确匹配，无自定义 SQL。
 */
public interface UserMapper extends BaseMapper<User> {
}
