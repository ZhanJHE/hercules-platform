package com.zhanjh.hercules.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zhanjh.hercules.mapper.UserMapper;
import com.zhanjh.hercules.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 演示账号播种器：应用启动时幂等写入种子账号（BCrypt 密文在启动时生成）。
 *
 * <p>为什么不写进 data.sql：BCrypt 密文含随机盐，静态写入要么硬编码「已泄露」的
 * 固定密文、要么无法保证正确性；由启动器调用 PasswordEncoder 现场编码最稳妥，
 * 且幂等（按 username 查重）。
 *
 * <p>种子账号（答辩演示用，生产环境必须删除）：
 * <ul>
 *   <li>admin / admin123 —— ADMIN（student_id = null）</li>
 *   <li>st001 / 123456 —— STUDENT（student_id = 20240001）</li>
 *   <li>st002 / 123456 —— STUDENT（student_id = 20240002）</li>
 *   <li>st003 / 123456 —— STUDENT（student_id = 20240003）</li>
 * </ul>
 *
 * <p>线程安全性：启动期单线程执行。
 *
 * @author zhanjh
 * @since 0.0.1
 */
@Component
public class AuthUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthUserSeeder.class);

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    /** 演示学生账号数量（hercules.auth.demo-student-count）：st001~st{N}，student_id=20240001..N；压测期 compose 调大为 60。 */
    private final int demoStudentCount;

    public AuthUserSeeder(UserMapper userMapper,
                          PasswordEncoder passwordEncoder,
                          @org.springframework.beans.factory.annotation.Value("${hercules.auth.demo-student-count:3}")
                          int demoStudentCount) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.demoStudentCount = demoStudentCount;
    }

    /**
     * 启动时幂等播种账号：管理员 1 个 + 学生账号 demoStudentCount 个（st001~st{N}，
     * student_id=20240001 起连续编号），按 username 查重，存在即跳过。
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        seed("admin", "admin123", User.ROLE_ADMIN, null);
        for (int i = 1; i <= demoStudentCount; i++) {
            seed(String.format("st%03d", i), "123456", User.ROLE_STUDENT, 20240000L + i);
        }
    }

    /**
     * 幂等播种单个账号：username 已存在则跳过。
     *
     * @param username  登录名
     * @param rawPassword 明文密码（仅启动期内存中存在，BCrypt 后入库）
     * @param role      角色
     * @param studentId 学生业务号（管理员为 null）
     */
    private void seed(String username, String rawPassword, String role, Long studentId) {
        Long exists = userMapper.selectCount(new QueryWrapper<User>().eq("username", username));
        if (exists != null && exists > 0) {
            return;
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        user.setStudentId(studentId);
        user.setStatus(1);
        user.setCreateTime(LocalDateTime.now());
        userMapper.insert(user);
        log.info("[hercules-auth] seeded demo user '{}' ({})", username, role);
    }
}
