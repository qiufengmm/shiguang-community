package com.quanxiaoha.xiaohashu.note.biz.controller;

import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.note.biz.enums.NoteStatusEnum;
import com.quanxiaoha.xiaohashu.note.biz.enums.NoteVisibleEnum;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only adapters for the original Vue frontend in the local Docker deployment.
 * The tutorial's existing service methods remain unchanged.
 */
@Profile("docker")
@RestController
public class DockerFrontendController {

    private static final int PAGE_SIZE = 10;
    private static final String CARD_SELECT = """
            SELECT n.id, n.type, n.img_uris, n.video_uri, n.title, n.creator_id,
                   u.avatar, u.nickname, COALESCE(c.like_total, 0) AS like_total
            FROM t_note n
            LEFT JOIN t_user u ON u.id = n.creator_id
            LEFT JOIN t_note_count c ON c.note_id = n.id
            """;

    private static final RowMapper<NamedItem> NAMED_ITEM_MAPPER = (rs, rowNum) ->
            new NamedItem(rs.getLong("id"), rs.getString("name"));

    private static final RowMapper<NoteCard> CARD_MAPPER = (rs, rowNum) -> {
        String images = rs.getString("img_uris");
        String cover = images == null || images.isBlank() ? null : images.split(",", 2)[0].trim();
        return new NoteCard(Long.toString(rs.getLong("id")), rs.getInt("type"), cover,
                rs.getString("video_uri"), rs.getString("title"), Long.toString(rs.getLong("creator_id")),
                rs.getString("avatar"), rs.getString("nickname"), rs.getLong("like_total"));
    };

    private final JdbcTemplate jdbcTemplate;

    public DockerFrontendController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/channel/list")
    public Response<List<NamedItem>> channels() {
        return Response.success(jdbcTemplate.query(
                "SELECT id, name FROM t_channel WHERE is_deleted = 0 ORDER BY id", NAMED_ITEM_MAPPER));
    }

    @PostMapping("/topic/list")
    public Response<List<NamedItem>> topics(@Valid @RequestBody TopicRequest request) {
        String keyword = request.keyword() == null ? "" : request.keyword().trim();
        if (keyword.isEmpty()) {
            return Response.success(jdbcTemplate.query(
                    "SELECT id, name FROM t_topic WHERE is_deleted = 0 ORDER BY id LIMIT 20",
                    NAMED_ITEM_MAPPER));
        }
        // Escape LIKE metacharacters so a search remains a literal substring search.
        String search = "%" + keyword.replace("!", "!!").replace("%", "!%")
                .replace("_", "!_") + "%";
        return Response.success(jdbcTemplate.query(
                "SELECT id, name FROM t_topic WHERE is_deleted = 0 AND name LIKE ? ESCAPE '!' ORDER BY id LIMIT 20",
                NAMED_ITEM_MAPPER, search));
    }

    @PostMapping("/discover/note/list")
    public Response<List<NoteCard>> discover(@Valid @RequestBody DiscoverRequest request) {
        StringBuilder sql = new StringBuilder(CARD_SELECT)
                .append(" WHERE n.status = ? AND n.visible = ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(NoteStatusEnum.NORMAL.getCode());
        parameters.add(NoteVisibleEnum.PUBLIC.getCode());

        if (request.channelId() != null && request.channelId() > 0) {
            sql.append("""
                     AND EXISTS (
                         SELECT 1 FROM t_channel_topic_rel r
                         JOIN t_channel ch ON ch.id = r.channel_id AND ch.is_deleted = 0
                         JOIN t_topic t ON t.id = r.topic_id AND t.is_deleted = 0
                         WHERE r.topic_id = n.topic_id AND r.channel_id = ?
                     )
                    """);
            parameters.add(request.channelId());
        }
        sql.append(" ORDER BY n.create_time DESC, n.id DESC LIMIT ? OFFSET ?");
        addPagination(parameters, request.pageNo());
        return Response.success(jdbcTemplate.query(sql.toString(), CARD_MAPPER, parameters.toArray()));
    }

    @PostMapping("/profile/note/list")
    public Response<List<NoteCard>> profile(@Valid @RequestBody ProfileRequest request) {
        StringBuilder sql = new StringBuilder(CARD_SELECT);
        List<Object> parameters = new ArrayList<>();
        switch (request.type()) {
            case 1 -> sql.append(" WHERE n.creator_id = ?");
            // Existing tutorial schema uses 1 for active likes/collections and 0 for cancellation.
            case 2 -> sql.append(" JOIN t_note_collection r ON r.note_id = n.id AND r.status = 1 WHERE r.user_id = ?");
            case 3 -> sql.append(" JOIN t_note_like r ON r.note_id = n.id AND r.status = 1 WHERE r.user_id = ?");
            default -> throw new IllegalArgumentException("type must be 1, 2, or 3");
        }
        parameters.add(request.userId());
        sql.append(" AND n.status = ?");
        parameters.add(NoteStatusEnum.NORMAL.getCode());

        Long currentUserId = LoginUserContextHolder.getUserId();
        sql.append(" AND (n.visible = ?");
        parameters.add(NoteVisibleEnum.PUBLIC.getCode());
        if (currentUserId != null) {
            // A private note is visible only to its creator, including in liked/collected lists.
            sql.append(" OR (n.visible = ? AND n.creator_id = ?)");
            parameters.add(NoteVisibleEnum.PRIVATE.getCode());
            parameters.add(currentUserId);
        }
        sql.append(")");
        if (request.type() == 1) {
            sql.append(" ORDER BY n.is_top DESC, n.create_time DESC, n.id DESC");
        } else {
            sql.append(" ORDER BY r.create_time DESC, n.id DESC");
        }
        sql.append(" LIMIT ? OFFSET ?");
        addPagination(parameters, request.pageNo());
        return Response.success(jdbcTemplate.query(sql.toString(), CARD_MAPPER, parameters.toArray()));
    }

    private static void addPagination(List<Object> parameters, int pageNo) {
        parameters.add(PAGE_SIZE);
        parameters.add((long) (pageNo - 1) * PAGE_SIZE);
    }

    public record TopicRequest(@Size(max = 100) String keyword) {
    }

    public record DiscoverRequest(@PositiveOrZero Long channelId,
                                  @NotNull @Min(1) @Max(10000) Integer pageNo) {
    }

    public record ProfileRequest(@NotNull @Min(1) @Max(3) Integer type,
                                 @NotNull @Positive Long userId,
                                 @NotNull @Min(1) @Max(10000) Integer pageNo) {
    }

    public record NamedItem(long id, String name) {
    }

    public record NoteCard(String id, int type, String cover, String videoUri, String title,
                           String creatorId, String avatar, String nickname, long likeTotal) {
    }
}
