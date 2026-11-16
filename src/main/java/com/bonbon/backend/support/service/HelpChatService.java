package com.bonbon.backend.support.service;

import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.ratelimit.RateLimiter;
import com.bonbon.backend.support.dto.HelpChatDtos;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The help-center chatbot (chat-with-chatbot.md): rule-based on purpose, with no language model anywhere. A question is folded (lower
 * case, no accents, no punctuation) and every published article for the asker's audience is scored by the keywords the administrator
 * gave it that appear in the question as whole words, longer phrases counting more, with the title words breaking ties. The best article
 * answers if at least one keyword matched; otherwise the asker is sent to the help center. Each answer comes from one article an
 * administrator wrote, so nothing is made up, and every question is logged so the unanswered ones can become new articles.
 */
@Service
public class HelpChatService {

    static final int MIN_SCORE = 10;
    private static final int PER_MINUTE = 20;
    private static final Set<String> STOP_WORDS = Set.of("la", "va", "cua", "co", "khong", "toi", "minh", "the", "nao", "sao", "gi", "duoc", "cho",
            "ban", "voi", "thi", "ma", "nhu", "lam", "o", "a", "e", "oi", "nhe", "vay", "khi", "neu", "de", "tai");

    private final JdbcClient jdbc;
    private final Clock clock;
    private final RateLimiter limiter;

    HelpChatService(JdbcClient jdbc, Clock clock, RateLimiter limiter) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.limiter = limiter;
    }

    private record Article(UUID id, String title, String body, List<String> keywords) {
    }

    private record Scored(Article article, int score) {
    }

    @Transactional
    public HelpChatDtos.Answer ask(String question, String audience, UUID askedBy, String ip) {
        if (!limiter.tryAcquire("help-chat:" + (askedBy != null ? askedBy : ip), PER_MINUTE, Duration.ofMinutes(1))) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_QUESTIONS", "Bạn hỏi quá nhanh. Hãy thử lại sau một phút.");
        }
        String q = question.strip().replaceAll("\\s+", " ");
        List<Scored> ranked = rank(q, audience);
        Scored best = ranked.isEmpty() || ranked.get(0).score() < MIN_SCORE ? null : ranked.get(0);
        jdbc.sql("""
                insert into support.help_chat_log (id, question, matched_article_id, score, audience, asked_by, created_at)
                values (:id, :q, :article, :score, :audience, :by, :now)""")
                .param("id", UUID.randomUUID()).param("q", q).param("article", best == null ? null : best.article().id())
                .param("score", ranked.isEmpty() ? 0 : ranked.get(0).score()).param("audience", audience).param("by", askedBy)
                .param("now", Timestamp.from(clock.instant())).update();
        List<HelpChatDtos.Suggestion> more = ranked.stream().filter(s -> best == null || s != best).filter(s -> s.score() >= MIN_SCORE).limit(3)
                .map(s -> new HelpChatDtos.Suggestion(s.article().id(), s.article().title())).toList();
        if (best == null) {
            return new HelpChatDtos.Answer(false, null, null, "Không tìm thấy câu trả lời phù hợp. Bạn thử hỏi bằng từ khác, hoặc xem trung tâm trợ giúp.", more);
        }
        return new HelpChatDtos.Answer(true, best.article().id(), best.article().title(), best.article().body(), more);
    }

    /** Recent questions nothing answered, the same question (folded) counted once with how often it was asked. */
    @Transactional(readOnly = true)
    public List<HelpChatDtos.Unanswered> unanswered(int days, int limit) {
        if (days < 1 || days > 90 || limit < 1 || limit > 200) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_RANGE", "Khoảng ngày 1–90, số dòng 1–200.");
        }
        Timestamp since = Timestamp.from(clock.instant().minus(Duration.ofDays(days)));
        record Row(String question, String audience, java.time.Instant at) {
        }
        List<Row> rows = jdbc.sql("""
                select question, audience, created_at from support.help_chat_log
                where matched_article_id is null and created_at >= :since order by created_at desc limit 2000""")
                .param("since", since).query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant())).list();
        Map<String, HelpChatDtos.Unanswered> grouped = new HashMap<>();
        for (Row r : rows) {
            grouped.merge(String.join(" ", words(r.question())), new HelpChatDtos.Unanswered(r.question(), r.audience(), 1, r.at()),
                    (a, b) -> new HelpChatDtos.Unanswered(a.question(), a.audience(), a.times() + 1, a.lastAskedAt()));
        }
        return grouped.values().stream().sorted(Comparator.comparingLong(HelpChatDtos.Unanswered::times).reversed()
                .thenComparing(HelpChatDtos.Unanswered::lastAskedAt, Comparator.reverseOrder())).limit(limit).toList();
    }

    private List<Scored> rank(String question, String audience) {
        List<String> qWords = words(question);
        String folded = " " + String.join(" ", qWords) + " ";
        Set<String> meaningful = new HashSet<>(qWords);
        meaningful.removeAll(STOP_WORDS);
        Map<UUID, Article> articles = new HashMap<>();
        jdbc.sql("""
                select a.id, a.title, a.body, k.keyword from support.help_articles a
                left join support.help_article_keywords k on k.article_id = a.id
                where a.status = 'PUBLISHED' and a.audience in ('ALL', :audience)""")
                .param("audience", audience)
                .query((rs, n) -> {
                    String title = rs.getString("title");
                    String body = rs.getString("body");
                    Article a = articles.computeIfAbsent(rs.getObject("id", UUID.class), id -> new Article(id, title, body, new ArrayList<>()));
                    String k = rs.getString("keyword");
                    if (k != null) {
                        a.keywords().add(k);
                    }
                    return 0;
                }).list();
        List<Scored> scored = new ArrayList<>();
        for (Article a : articles.values()) {
            int score = 0;
            for (String keyword : a.keywords()) {
                List<String> kw = words(keyword);
                if (!kw.isEmpty() && folded.contains(" " + String.join(" ", kw) + " ")) {
                    score += 10 * kw.size() + String.join("", kw).length();
                }
            }
            for (String w : words(a.title())) {
                if (w.length() >= 3 && meaningful.contains(w)) {
                    score += 1;
                }
            }
            if (score > 0) {
                scored.add(new Scored(a, score));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed().thenComparing(s -> s.article().title()));
        return scored;
    }

    /** Lower-case words without accents or punctuation: how the question and the keywords are compared. */
    static List<String> words(String text) {
        if (text == null) {
            return List.of();
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'D').toLowerCase();
        return Arrays.stream(folded.split("[^a-z0-9]+")).filter(w -> !w.isEmpty()).toList();
    }
}
