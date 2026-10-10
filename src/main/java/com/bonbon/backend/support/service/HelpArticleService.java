package com.bonbon.backend.support.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.support.dto.HelpRequests;
import com.bonbon.backend.support.dto.HelpViews;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The help centre (flows/support/browse-help-center.md, manage-help-center-content.md). Readers only ever see published
 * articles meant for them; which audience a reader belongs to comes from who they are, never from a parameter, so a customer
 * cannot fish for seller-only articles.
 */
@Service
public class HelpArticleService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int PUBLIC_LIMIT = 50;

    private final JdbcClient jdbc;
    private final Clock clock;

    HelpArticleService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // --- readers

    /** {@code role} is the caller's active role, or null for a guest: sellers see their own articles, everyone else the customer's. */
    @Transactional(readOnly = true)
    public HelpViews.Articles search(String role, String query) {
        String own = "SELLER".equals(role) ? "SELLER" : "CUSTOMER";
        if ("ADMIN".equals(role)) {
            own = "ALL";
        }
        List<UUID> ids = jdbc.sql("select a.id from help_articles a where a.status = 'PUBLISHED' and a.audience in ('ALL', :own)" + matching(query)
                + " order by a.updated_at desc, a.title limit :limit").params(searchParams(query)).param("own", own).param("limit", PUBLIC_LIMIT).query(UUID.class).list();
        return new HelpViews.Articles(ids.stream().map(this::article).toList());
    }

    @Transactional(readOnly = true)
    public HelpViews.Article get(String role, UUID id) {
        String own = "SELLER".equals(role) ? "SELLER" : "CUSTOMER";
        if ("ADMIN".equals(role)) {
            own = "ALL";
        }
        boolean visible = jdbc.sql("select count(*) from help_articles where id = :id and status = 'PUBLISHED' and audience in ('ALL', :own)")
                .param("id", id).param("own", own).query(Long.class).single() > 0;
        if (!visible) {
            throw notFound();
        }
        return article(id);
    }

    // --- administrators

    @Transactional(readOnly = true)
    public HelpViews.AdminArticles list(String status, String query, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
        if (status != null && !status.equals("DRAFT") && !status.equals("PUBLISHED")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "`status` phải là DRAFT hoặc PUBLISHED.");
        }
        String where = " where (cast(:status as text) is null or a.status = :status)" + matching(query);
        Map<String, Object> params = searchParams(query);
        params.put("status", status);
        long total = jdbc.sql("select count(*) from help_articles a" + where).params(params).query(Long.class).single();
        List<UUID> ids = jdbc.sql("select a.id from help_articles a" + where + " order by a.updated_at desc, a.title limit :limit offset :offset").params(params)
                .param("limit", size).param("offset", (long) page * size).query(UUID.class).list();
        return new HelpViews.AdminArticles(ids.stream().map(this::adminArticle).toList(), page, size, total);
    }

    @Transactional(readOnly = true)
    public HelpViews.AdminArticle adminGet(UUID id) {
        return adminArticle(id);
    }

    @Transactional
    public HelpViews.AdminArticle create(UUID adminId, HelpRequests.Create request) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("insert into help_articles (id, title, body, audience, status, created_by, created_at, updated_at) values (:id, :title, :body, :audience, :status, :admin, :now, :now)")
                .param("id", id).param("title", request.title().strip()).param("body", request.body().strip()).param("audience", request.audience())
                .param("status", request.status() == null ? "DRAFT" : request.status()).param("admin", adminId).param("now", now).update();
        keywords(id, request.keywords());
        return adminArticle(id);
    }

    @Transactional
    public HelpViews.AdminArticle update(UUID id, HelpRequests.Update request) {
        adminArticle(id);
        jdbc.sql("""
                update help_articles set title = coalesce(:title, title), body = coalesce(:body, body), audience = coalesce(:audience, audience),
                       status = coalesce(:status, status), updated_at = :now where id = :id""")
                .param("id", id).param("title", blankToNull(request.title())).param("body", blankToNull(request.body())).param("audience", request.audience())
                .param("status", request.status()).param("now", Timestamp.from(clock.instant())).update();
        if (request.keywords() != null) {
            jdbc.sql("delete from help_article_keywords where article_id = :id").param("id", id).update();
            keywords(id, request.keywords());
        }
        return adminArticle(id);
    }

    @Transactional
    public void delete(UUID id) {
        if (jdbc.sql("delete from help_articles where id = :id").param("id", id).update() == 0) {
            throw notFound();
        }
    }

    // --- internals

    private HelpViews.Article article(UUID id) {
        return jdbc.sql("select id, title, body, audience, updated_at from help_articles where id = :id").param("id", id)
                .query((rs, n) -> new HelpViews.Article(id, rs.getString("title"), rs.getString("body"), rs.getString("audience"), keywordsOf(id),
                        rs.getTimestamp("updated_at").toInstant()))
                .optional().orElseThrow(HelpArticleService::notFound);
    }

    private HelpViews.AdminArticle adminArticle(UUID id) {
        return jdbc.sql("select id, title, body, audience, status, created_at, updated_at from help_articles where id = :id").param("id", id)
                .query((rs, n) -> new HelpViews.AdminArticle(id, rs.getString("title"), rs.getString("body"), rs.getString("audience"), rs.getString("status"), keywordsOf(id),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()))
                .optional().orElseThrow(HelpArticleService::notFound);
    }

    private List<String> keywordsOf(UUID id) {
        return jdbc.sql("select keyword from help_article_keywords where article_id = :id order by keyword").param("id", id).query(String.class).list();
    }

    private void keywords(UUID id, List<String> keywords) {
        if (keywords == null) {
            return;
        }
        Set<String> unique = keywords.stream().map(k -> k.strip().toLowerCase(Locale.ROOT)).filter(k -> !k.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new));
        for (String keyword : unique) {
            jdbc.sql("insert into help_article_keywords (article_id, keyword) values (:id, :k)").param("id", id).param("k", keyword).update();
        }
    }

    /** Title, body and keywords, ignoring case and accents, the way shops and dishes are searched. */
    private static String matching(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        return """
                 and (unaccent(lower(a.title)) like unaccent(:pattern) or unaccent(lower(a.body)) like unaccent(:pattern)
                      or exists (select 1 from help_article_keywords k where k.article_id = a.id and unaccent(lower(k.keyword)) like unaccent(:pattern)))""";
    }

    private static Map<String, Object> searchParams(String query) {
        Map<String, Object> params = new java.util.HashMap<>();
        if (query != null && !query.isBlank()) {
            String escaped = query.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            params.put("pattern", "%" + escaped + "%");
        }
        return params;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static BusinessException notFound() {
        return BusinessException.notFound("ARTICLE_NOT_FOUND", "Không tìm thấy bài trợ giúp.");
    }
}
