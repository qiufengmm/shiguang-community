package com.quanxiaoha.xiaohashu.kv.biz.domain.dataobject;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyClass;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@PrimaryKeyClass
public class CommentContentPrimaryKey {

    @PrimaryKeyColumn(name = "note_id", type = PrimaryKeyType.PARTITIONED)
    private Long noteId;

    @PrimaryKeyColumn(name = "year_month", type = PrimaryKeyType.PARTITIONED)
    private String yearMonth;

    @PrimaryKeyColumn(name = "content_id", type = PrimaryKeyType.CLUSTERED)
    private UUID contentId;
}
