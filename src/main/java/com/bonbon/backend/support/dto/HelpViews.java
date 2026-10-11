package com.bonbon.backend.support.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public final class HelpViews {

    private HelpViews() {
    }

    @Schema(name = "HelpArticleSummary", description = "Bài trợ giúp trong danh sách, kèm nội dung đầy đủ vì bài ngắn.")
    public record Article(UUID id, String title, String body, String audience, List<String> keywords, Instant updatedAt) {
    }

    @Schema(name = "HelpArticleList")
    public record Articles(List<Article> items) {
    }

    @Schema(name = "AdminHelpArticle")
    public record AdminArticle(UUID id, String title, String body, String audience, String status, List<String> keywords, Instant createdAt, Instant updatedAt) {
    }

    @Schema(name = "AdminHelpArticleList")
    public record AdminArticles(List<AdminArticle> items, int page, int size, long total) {
    }
}
