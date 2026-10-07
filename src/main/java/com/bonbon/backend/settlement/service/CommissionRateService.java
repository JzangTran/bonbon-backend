package com.bonbon.backend.settlement.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.category.CategoryCatalog;
import com.bonbon.backend.category.CategoryCommissionChanged;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.settlement.dto.CommissionViews;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The commission rates the administrator controls (flows/settlement/set-commission-rate.md): a global default, a rate
 * on any category that its descendants inherit, and a history that is only ever appended to. Orders read the rate in
 * force when they are placed and snapshot it, so a change never touches an existing order.
 */
@Service
public class CommissionRateService {

    static final String DEFAULT_RATE_KEY = "commission.default_rate";
    static final String VAT_KEY = "commission.vat_percent";
    static final BigDecimal MAX_RATE = new BigDecimal("30");
    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcClient jdbc;
    private final SystemSettingsService settings;
    private final CategoryCatalog categories;

    CommissionRateService(JdbcClient jdbc, SystemSettingsService settings, CategoryCatalog categories) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.categories = categories;
    }

    /** A VAT-inclusive commission split into its net part and its VAT part (statements show them apart). */
    public record Split(int gross, int net, int vat) {
    }

    /** {@code net = gross / (1 + vat)} rounded half up; the VAT part is what remains, so the two always add up. */
    public Split split(int gross) {
        BigDecimal factor = BigDecimal.ONE.add(vatPercent().movePointLeft(2));
        int net = new BigDecimal(gross).divide(factor, 0, RoundingMode.HALF_UP).intValue();
        return new Split(gross, net, gross - net);
    }

    public BigDecimal vatPercent() {
        return new BigDecimal(settings.getString(VAT_KEY, "8"));
    }

    public BigDecimal defaultRate() {
        return new BigDecimal(settings.getString(DEFAULT_RATE_KEY, "10"));
    }

    @Transactional(readOnly = true)
    public CommissionViews.Rates rates() {
        BigDecimal fallback = defaultRate();
        List<CategoryCatalog.RateNode> nodes = categories.rateNodes();
        Map<UUID, CategoryCatalog.RateNode> byId = new HashMap<>();
        nodes.forEach(n -> byId.put(n.id(), n));
        List<CommissionViews.CategoryRate> rows = new ArrayList<>();
        for (CategoryCatalog.RateNode node : nodes) {
            CategoryCatalog.RateNode source = node.ownRate() != null ? node : null;
            for (UUID p = node.parentId(); source == null && p != null; p = byId.get(p).parentId()) {
                if (byId.get(p).ownRate() != null) {
                    source = byId.get(p);
                }
            }
            String kind = source == null ? "DEFAULT" : source == node ? "OWN" : "ANCESTOR";
            rows.add(new CommissionViews.CategoryRate(node.id(), node.parentId(), node.level(), node.name(), node.active(), node.ownRate(),
                    source == null ? fallback : source.ownRate(), kind, "ANCESTOR".equals(kind) ? source.name() : null));
        }
        return new CommissionViews.Rates(fallback, MAX_RATE, vatPercent(), rows);
    }

    @Transactional
    public CommissionViews.Rates setDefault(CurrentPrincipal actor, BigDecimal rate) {
        BigDecimal before = defaultRate();
        if (before.compareTo(rate) != 0) {
            settings.set(DEFAULT_RATE_KEY, rate.stripTrailingZeros().toPlainString(), actor.actorType(), actor.id());
            jdbc.sql("""
                    insert into commission_rate_history (scope, rate, previous_rate, acted_by_type, acted_by_id)
                    values ('DEFAULT', :rate, :before, :type, :actor)""")
                    .param("rate", rate).param("before", before).param("type", actor.actorType().name()).param("actor", actor.id()).update();
        }
        return rates();
    }

    @Transactional
    public CommissionViews.Rates setCategory(CurrentPrincipal actor, UUID categoryId, BigDecimal rate) {
        categories.changeCommissionRate(categoryId, rate, actor.actorType(), actor.id());
        return rates();
    }

    /** The history keeps one row per real change, whichever way it was made (this module or the category screen). */
    @EventListener
    void onCategoryRateChanged(CategoryCommissionChanged event) {
        jdbc.sql("""
                insert into commission_rate_history (scope, category_id, category_name, rate, previous_rate, acted_by_type, acted_by_id)
                values ('CATEGORY', :category, :name, :rate, :before, :type, :actor)""")
                .param("category", event.categoryId()).param("name", event.categoryName()).param("rate", event.rate())
                .param("before", event.previousRate()).param("type", event.by().name()).param("actor", event.actorId()).update();
    }

    @Transactional(readOnly = true)
    public CommissionViews.History history(String scope, UUID categoryId, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
        if (scope != null && !Set.of("DEFAULT", "CATEGORY").contains(scope)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SCOPE", "`scope` chỉ nhận DEFAULT hoặc CATEGORY.");
        }
        StringBuilder where = new StringBuilder(" where true");
        Map<String, Object> params = new HashMap<>();
        if (scope != null) {
            where.append(" and scope = :scope");
            params.put("scope", scope);
        }
        if (categoryId != null) {
            where.append(" and category_id = :category");
            params.put("category", categoryId);
        }
        long total = jdbc.sql("select count(*) from commission_rate_history" + where).params(params).query(Long.class).single();
        List<CommissionViews.Change> items = jdbc.sql("""
                select id, scope, category_id, category_name, rate, previous_rate, effective_from, acted_by_type, acted_by_id
                from commission_rate_history""" + where + " order by effective_from desc, id limit :limit offset :offset")
                .params(params).param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> new CommissionViews.Change(rs.getObject("id", UUID.class), rs.getString("scope"),
                        rs.getObject("category_id", UUID.class), rs.getString("category_name"), rs.getBigDecimal("rate"),
                        rs.getBigDecimal("previous_rate"), rs.getTimestamp("effective_from").toInstant(), rs.getString("acted_by_type"),
                        rs.getObject("acted_by_id", UUID.class))).list();
        return new CommissionViews.History(items, page, size, total);
    }
}
