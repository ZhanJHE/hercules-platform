package com.zhanjh.hercules.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局 Jackson 门面：全局唯一 ObjectMapper 配置入口，向量时钟、缓存值（Course/PageResult 的序列化串）与 t_cache_version 版本记录均以 JSON 字符串承载。
 *
 * <p>MAPPER 关键配置及动机：
 * <ul>
 *   <li>注册 JavaTimeModule 并关闭 WRITE_DATES_AS_TIMESTAMPS —— LocalDateTime 等序列化为 ISO-8601 字符串
 *       （如 "2025-01-01T10:00:00"），缓存值可读且格式跨版本稳定；</li>
 *   <li>关闭 FAIL_ON_UNKNOWN_PROPERTIES —— 反序列化忽略未知字段，实体后续加列后 Redis/L1 中的旧 JSON 仍可解析，保证缓存兼容；</li>
 *   <li>序列化 NON_NULL —— null 字段不输出，减小缓存值体积。</li>
 * </ul>
 *
 * <p>线程安全性：ObjectMapper 配置完成后线程安全，静态单例并发复用；私有构造器禁止实例化。
 * 解析/序列化失败统一抛非受检 IllegalStateException（原始 JsonProcessingException 作为 cause）：
 * MVP 语义下 JSON 损坏属编程/数据错误，不作为可恢复业务异常逐层上抛。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public final class JsonUtil {

    /** 全局唯一共享 ObjectMapper（配置细节见类注释），供本工具与 mapper() 复用。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /** 工具类，禁止实例化。 */
    private JsonUtil() {
    }

    /**
     * 暴露全局 ObjectMapper，供需要直接定制读写（如注册自定义模块）的调用方复用同一份配置。
     *
     * @return 共享 ObjectMapper 实例（线程安全；调用方不应修改其全局配置）
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * 序列化为 JSON 字符串（NON_NULL，null 字段不输出）。
     *
     * @param obj 待序列化对象，null 将输出为 "null"
     * @return JSON 字符串，非 null
     * @throws IllegalStateException 序列化失败（如出现不可序列化类型），原 JsonProcessingException 作为 cause
     */
    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON serialize error: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * 反序列化为指定类型（忽略未知字段）。
     *
     * @param json JSON 字符串
     * @param type 目标类型
     * @param <T>  目标类型
     * @return 反序列化结果；json 为 null 或空白时返回 null（空输入视为无值而非解析错误，调用方据此走回源逻辑）
     * @throws IllegalStateException JSON 非法或与目标类型不匹配
     */
    public static <T> T parse(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON parse error: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * 反序列化为泛型类型（如 TypeReference&lt;PageResult&lt;Course&gt;&gt;），供列表缓存值保留完整类型信息。
     *
     * @param json    JSON 字符串
     * @param typeRef 描述完整泛型类型的 TypeReference
     * @param <T>     目标类型
     * @return 反序列化结果；json 为 null 或空白时返回 null
     * @throws IllegalStateException JSON 非法或与目标类型不匹配
     */
    public static <T> T parse(String json, TypeReference<T> typeRef) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON parse error: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * 反序列化为 String→Object 的 Map（如向量时钟 JSON {"node-1":3}）。
     *
     * @param json JSON 对象字符串
     * @return Map 结果，非 null；json 为 null 或空白时返回空 LinkedHashMap（调用方免判空）；
     *         正常解析为 LinkedHashMap，保持 JSON 中键的原有顺序
     * @throws IllegalStateException JSON 非法或顶层不是 JSON 对象
     */
    public static Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON parse error: " + e.getOriginalMessage(), e);
        }
    }
}
