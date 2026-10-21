package com.quanxiaoha.xiaohashu.data.align.job;

/** Count entities tracked by the daily marker tables. */
public enum CountAuditMetric {
    FOLLOWING("t_data_align_following_count_temp_", "user_id"),
    FANS("t_data_align_fans_count_temp_", "user_id"),
    NOTE_COLLECT("t_data_align_note_collect_count_temp_", "note_id"),
    USER_COLLECT("t_data_align_user_collect_count_temp_", "user_id"),
    USER_LIKE("t_data_align_user_like_count_temp_", "user_id"),
    NOTE_LIKE("t_data_align_note_like_count_temp_", "note_id"),
    NOTE_PUBLISH("t_data_align_note_publish_count_temp_", "user_id");

    private final String tablePrefix;
    private final String entityColumn;

    CountAuditMetric(String tablePrefix, String entityColumn) {
        this.tablePrefix = tablePrefix;
        this.entityColumn = entityColumn;
    }

    public String getTablePrefix() {
        return tablePrefix;
    }

    public String getEntityColumn() {
        return entityColumn;
    }
}
