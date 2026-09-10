package com.zhanjh.hercules.model.sync;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Binlog 行变更模型（阶段 B）：canal flatMessage JSON 解析后的中间表示，
 * 是「原始 binlog 行数据」与「版本化值 VersionedValue」之间的解耦层。
 *
 * <p>承载内容：库表名、变更类型、主键值、变更后整行数据（列名 → 字符串值，
 * canal flatMessage 的 data 即为字符串 Map）、事件执行时间与 binlog 位点。
 * 字段值统一以 String 承载（与 canal flatMessage 语义一致），类型还原由转换层
 * （BinlogToVersionConverter 的行映射）负责。
 *
 * <p>对应《包结构说明》规划：原置于 hercules-common（model/sync 包），与 ChangeType 同包。
 * 纯数据模型（可变 POJO：解析层逐字段赋值后只读使用）。
 *
 * @author zhanjh
 * @since 0.0.1
 */
public class BinlogEntry {

    /** 库名（canal flatMessage database），如 "hercules"。 */
    private String database;

    /** 表名（canal flatMessage table），如 "t_course"。 */
    private String table;

    /** 变更类型：INSERT / UPDATE / DELETE。 */
    private ChangeType type;

    /** 变更后整行数据（列名 → 值字符串）；DELETE 事件可能为空 Map。 */
    private Map<String, String> data;

    /** binlog 事件执行时间（毫秒 epoch，canal executeTime）：作为 LWW 裁决的新值时间戳。 */
    private long executeTime;

    /** binlog 位点（logfileName:offset 简并字符串），仅用于日志排查，不参与逻辑。 */
    private String position;

    /** 事件创建时间（解析侧记录，排查用）。 */
    private LocalDateTime parsedAt;

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public ChangeType getType() {
        return type;
    }

    public void setType(ChangeType type) {
        this.type = type;
    }

    public Map<String, String> getData() {
        return data;
    }

    public void setData(Map<String, String> data) {
        this.data = data;
    }

    public long getExecuteTime() {
        return executeTime;
    }

    public void setExecuteTime(long executeTime) {
        this.executeTime = executeTime;
    }

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }

    public LocalDateTime getParsedAt() {
        return parsedAt;
    }

    public void setParsedAt(LocalDateTime parsedAt) {
        this.parsedAt = parsedAt;
    }
}
