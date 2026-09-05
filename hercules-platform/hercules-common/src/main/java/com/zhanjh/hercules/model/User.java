package com.zhanjh.hercules.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 认证用户表 t_user（阶段 A+ 新增，起步文档 §5 设计增补）。
 *
 * <p>角色：STUDENT（学生）/ ADMIN（管理·运维）。管理员 student_id 为 null；
 * 学生账号的 studentId 即选课业务号（t_enrollment.student_id），由服务端从认证上下文取用，
 * 前端传参不生效（水平越权收敛，见《认证设计.md》）。
 *
 * <p>密码：BCrypt 密文（强度 10，VARCHAR(60)），由 AuthUserSeeder 启动时幂等播种。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@TableName("t_user")
public class User {

    /** 角色常量：学生 */
    public static final String ROLE_STUDENT = "STUDENT";
    /** 角色常量：管理员 */
    public static final String ROLE_ADMIN = "ADMIN";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名，唯一 */
    private String username;

    /** BCrypt 密文（VARCHAR(60)），禁止存明文 */
    private String password;

    /** 角色：STUDENT / ADMIN */
    private String role;

    /** 学生业务号（选课用）；管理员为 null */
    private Long studentId;

    /** 1 启用 0 停用 */
    private Integer status;

    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Long getStudentId() {
        return studentId;
    }

    public void setStudentId(Long studentId) {
        this.studentId = studentId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
