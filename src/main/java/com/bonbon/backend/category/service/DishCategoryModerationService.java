package com.bonbon.backend.category.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.category.CategoryCatalog;
import com.bonbon.backend.category.DishCategoryCaseChanged;
import com.bonbon.backend.category.DishCategoryCases;
import com.bonbon.backend.category.DishCategoryGateway;
import com.bonbon.backend.category.dto.ModerationRequests;
import com.bonbon.backend.category.dto.ModerationViews;
import com.bonbon.backend.category.entity.Category;
import com.bonbon.backend.category.repository.CategoryRepository;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.settings.SystemSettingsService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A dish filed in the wrong category (flows/category/moderate-dish-category.md): commission follows the dish's category and the shop
 * chooses it, so an administrator can flag a dish. It is dropped from search until fixed (RESTRICTED); the seller fixes it, or the system
 * applies the administrator's suggestion at the deadline (AUTO_CORRECTED), after which the seller confirms or appeals within the appeal
 * window and an administrator decides. Every transition is a conditional update on the status, so a seller and the job cannot both win.
 * A change of category applies to orders placed afterwards only.
 */
@Service
public class DishCategoryModerationService implements DishCategoryCases {

    private static final Set<String> STATUSES = Set.of("RESTRICTED", "AUTO_CORRECTED", "APPEALED", "RESOLVED", "CONFIRMED", "EXPIRED", "APPEAL_ACCEPTED", "APPEAL_REJECTED");
    private static final int MAX_PAGE_SIZE = 100;
    private static final Duration MAX_DEADLINE = Duration.ofDays(7);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final DishCategoryGateway dishes;
    private final CategoryCatalog catalog;
    private final CategoryRepository categories;
    private final SystemSettingsService settings;
    private final ApplicationEventPublisher events;

    DishCategoryModerationService(JdbcClient jdbc, Clock clock, DishCategoryGateway dishes, CategoryCatalog catalog, CategoryRepository categories, SystemSettingsService settings,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.dishes = dishes;
        this.catalog = catalog;
        this.categories = categories;
        this.settings = settings;
        this.events = events;
    }

    // --- administrators

    @Transactional
    public ModerationViews.Case flag(CurrentPrincipal admin, UUID dishId, ModerationRequests.Flag request) {
        DishCategoryGateway.Dish dish = dishes.dish(dishId).orElseThrow(() -> BusinessException.notFound("DISH_NOT_FOUND", "Không tìm thấy món."));
        if (dish.archived()) {
            throw BusinessException.conflict("DISH_ARCHIVED", "Món đã bị xoá khỏi thực đơn.");
        }
        leaf(request.suggestedCategoryId());
        if (request.suggestedCategoryId().equals(dish.categoryId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SAME_CATEGORY", "Ngành gợi ý trùng với ngành hiện tại của món.");
        }
        Instant now = clock.instant();
        Instant deadline = request.deadlineAt() == null ? now.plus(Duration.ofHours(settings.getInt("category.fix_deadline_hours", 24))) : request.deadlineAt();
        if (!deadline.isAfter(now)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DEADLINE_IN_THE_PAST", "Hạn sửa phải ở tương lai.");
        }
        if (deadline.isAfter(now.plus(MAX_DEADLINE))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DEADLINE_TOO_FAR", "Hạn sửa tối đa 7 ngày.");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into dish_category_cases (id, dish_id, vendor_id, dish_name, shop_name, status, reason, original_category_id, suggested_category_id, deadline_at, opened_by_id, opened_at)
                    values (:id, :dish, :vendor, :name, :shop, 'RESTRICTED', :reason, :original, :suggested, :deadline, :admin, :now)""")
                    .param("id", id).param("dish", dishId).param("vendor", dish.vendorId()).param("name", dish.name()).param("shop", dish.shopName()).param("reason", request.reason().strip())
                    .param("original", dish.categoryId()).param("suggested", request.suggestedCategoryId()).param("deadline", Timestamp.from(deadline)).param("admin", admin.id())
                    .param("now", Timestamp.from(now)).update();
        } catch (DataIntegrityViolationException e) {
            throw BusinessException.conflict("CASE_OPEN", "Món này đang có một vụ chưa đóng; hãy xử lý vụ đó trước.");
        }
        dishes.setRestricted(dishId, true);
        events.publishEvent(new DishCategoryCaseChanged(id, dish.vendorId(), dishId, dish.name(), "FLAGGED", nameOf(request.suggestedCategoryId()), deadline, request.reason().strip()));
        return view(id);
    }

    /** The administrator changes the category outright; a case about the dish that is still the seller's to fix is closed with it. */
    @Transactional
    public ModerationViews.Case reclassify(CurrentPrincipal admin, UUID dishId, ModerationRequests.Reclassify request) {
        DishCategoryGateway.Dish dish = dishes.dish(dishId).orElseThrow(() -> BusinessException.notFound("DISH_NOT_FOUND", "Không tìm thấy món."));
        leaf(request.categoryId());
        Optional<UUID> open = jdbc.sql("select id from dish_category_cases where dish_id = :d and status in ('RESTRICTED', 'AUTO_CORRECTED', 'APPEALED')").param("d", dishId).query(UUID.class).optional();
        if (open.isPresent() && jdbc.sql("select status from dish_category_cases where id = :id").param("id", open.get()).query(String.class).single().equals("APPEALED")) {
            throw BusinessException.conflict("APPEAL_PENDING", "Quán đang kháng nghị món này; hãy quyết định kháng nghị thay vì đổi ngành.");
        }
        if (open.isPresent()) {
            jdbc.sql("""
                    update dish_category_cases set status = 'RESOLVED', final_category_id = :cat, decided_by_type = 'ADMIN', decided_by_id = :admin, decided_at = :now, decision_reason = :reason
                    where id = :id and status in ('RESTRICTED', 'AUTO_CORRECTED')""")
                    .param("cat", request.categoryId()).param("admin", admin.id()).param("now", Timestamp.from(clock.instant())).param("reason", request.reason().strip()).param("id", open.get()).update();
        }
        dishes.setCategory(dishId, request.categoryId());
        dishes.setRestricted(dishId, false);
        events.publishEvent(new DishCategoryCaseChanged(open.orElse(null), dish.vendorId(), dishId, dish.name(), "RECLASSIFIED", nameOf(request.categoryId()), null, request.reason().strip()));
        return open.map(this::view).orElse(null);
    }

    @Transactional(readOnly = true)
    public ModerationViews.Page list(String status, UUID vendorId, int page, int size) {
        return page(status, vendorId, page, size);
    }

    @Transactional(readOnly = true)
    public ModerationViews.Case get(UUID id) {
        return view(id);
    }

    @Transactional
    public ModerationViews.Case decide(CurrentPrincipal admin, UUID id, ModerationRequests.Decide request) {
        record Row(UUID dishId, UUID vendorId, String dishName, UUID appealCategory, UUID finalCategory) {
        }
        Row row = jdbc.sql("select dish_id, vendor_id, dish_name, appeal_category_id, final_category_id from dish_category_cases where id = :id").param("id", id)
                .query((rs, n) -> new Row(rs.getObject("dish_id", UUID.class), rs.getObject("vendor_id", UUID.class), rs.getString("dish_name"), rs.getObject("appeal_category_id", UUID.class),
                        rs.getObject("final_category_id", UUID.class))).optional().orElseThrow(DishCategoryModerationService::notFound);
        boolean accept = "ACCEPT".equals(request.decision());
        UUID target = accept ? (request.categoryId() != null ? request.categoryId() : row.appealCategory()) : row.finalCategory();
        if (accept) {
            leaf(target);
        }
        int changed = jdbc.sql("""
                update dish_category_cases set status = :status, final_category_id = :cat, decided_by_type = 'ADMIN', decided_by_id = :admin, decided_at = :now, decision_reason = :reason
                where id = :id and status = 'APPEALED'""")
                .param("status", accept ? "APPEAL_ACCEPTED" : "APPEAL_REJECTED").param("cat", target).param("admin", admin.id()).param("now", Timestamp.from(clock.instant()))
                .param("reason", request.reason().strip()).param("id", id).update();
        if (changed == 0) {
            throw notAppealed(id);
        }
        if (accept) {
            dishes.setCategory(row.dishId(), target);
        }
        events.publishEvent(new DishCategoryCaseChanged(id, row.vendorId(), row.dishId(), row.dishName(), accept ? "APPEAL_ACCEPTED" : "APPEAL_REJECTED", nameOf(target), null, request.reason().strip()));
        return view(id);
    }

    /** A shop's current dishes with their category path, commission rate and any open case: where an administrator finds a misfiled dish. */
    @Transactional(readOnly = true)
    public ModerationViews.ShopDishes dishesOf(UUID vendorId) {
        String shopName = dishes.shopName(vendorId).orElseThrow(() -> BusinessException.notFound("SHOP_NOT_FOUND", "Không tìm thấy cửa hàng."));
        java.util.Map<UUID, Category> byId = categories.findAllInTreeOrder().stream().collect(java.util.stream.Collectors.toMap(Category::getId, c -> c));
        java.util.Map<UUID, ModerationViews.OpenCase> open = new java.util.HashMap<>();
        jdbc.sql("select dish_id, id, status from dish_category_cases where vendor_id = :v and status in ('RESTRICTED', 'AUTO_CORRECTED', 'APPEALED')").param("v", vendorId)
                .query((rs, n) -> java.util.Map.entry(rs.getObject("dish_id", UUID.class), new ModerationViews.OpenCase(rs.getObject("id", UUID.class), rs.getString("status")))).list()
                .forEach(e -> open.put(e.getKey(), e.getValue()));
        List<ModerationViews.ShopDish> items = dishes.dishesOf(vendorId).stream().map(d -> new ModerationViews.ShopDish(d.id(), d.name(), d.price(), d.categoryId(),
                pathOf(byId, d.categoryId()), catalog.effectiveCommissionRate(d.categoryId()).orElse(null), d.restricted(), open.get(d.id()))).toList();
        return new ModerationViews.ShopDishes(vendorId, shopName, items);
    }

    /** From the level-2 category down to the leaf; the fixed root says nothing. */
    private static String pathOf(java.util.Map<UUID, Category> byId, UUID categoryId) {
        java.util.LinkedList<String> names = new java.util.LinkedList<>();
        for (Category c = byId.get(categoryId); c != null && c.getParentId() != null; c = byId.get(c.getParentId())) {
            names.addFirst(c.getName());
        }
        return String.join(" › ", names);
    }

    // --- the seller

    @Transactional(readOnly = true)
    public ModerationViews.Page mine(CurrentPrincipal seller, String status, int page, int size) {
        return page(status, shopOf(seller), page, size);
    }

    @Transactional(readOnly = true)
    public ModerationViews.Case mineOne(CurrentPrincipal seller, UUID id) {
        owned(shopOf(seller), id);
        return view(id);
    }

    /** The seller changes the category in answer to the case; it is closed whether they chose the suggestion or something else. */
    @Transactional
    public ModerationViews.Case resolve(CurrentPrincipal seller, UUID id, UUID categoryId) {
        UUID shop = shopOf(seller);
        owned(shop, id);
        leaf(categoryId);
        UUID dishId = jdbc.sql("select dish_id from dish_category_cases where id = :id").param("id", id).query(UUID.class).single();
        int changed = jdbc.sql("""
                update dish_category_cases set status = 'RESOLVED', final_category_id = :cat, decided_by_type = :type, decided_by_id = :who, decided_at = :now
                where id = :id and status in ('RESTRICTED', 'AUTO_CORRECTED')""")
                .param("cat", categoryId).param("type", seller.actorType().name()).param("who", seller.id()).param("now", Timestamp.from(clock.instant())).param("id", id).update();
        if (changed == 0) {
            throw notOpen(id);
        }
        dishes.setCategory(dishId, categoryId);
        dishes.setRestricted(dishId, false);
        return view(id);
    }

    @Transactional
    public ModerationViews.Case confirm(CurrentPrincipal seller, UUID id) {
        owned(shopOf(seller), id);
        Instant now = clock.instant();
        int changed = jdbc.sql("""
                update dish_category_cases set status = 'CONFIRMED', decided_by_type = :type, decided_by_id = :who, decided_at = :now
                where id = :id and status = 'AUTO_CORRECTED' and appeal_deadline_at > :now""")
                .param("type", seller.actorType().name()).param("who", seller.id()).param("now", Timestamp.from(now)).param("id", id).update();
        if (changed == 0) {
            throw notOpen(id);
        }
        return view(id);
    }

    @Transactional
    public ModerationViews.Case appeal(CurrentPrincipal seller, UUID id, ModerationRequests.Appeal request) {
        owned(shopOf(seller), id);
        leaf(request.categoryId());
        Instant now = clock.instant();
        int changed = jdbc.sql("""
                update dish_category_cases set status = 'APPEALED', appeal_category_id = :cat, appeal_note = :note, appealed_at = :now
                where id = :id and status = 'AUTO_CORRECTED' and appeal_deadline_at > :now""")
                .param("cat", request.categoryId()).param("note", request.note().strip()).param("now", Timestamp.from(now)).param("id", id).update();
        if (changed == 0) {
            throw notOpen(id);
        }
        return view(id);
    }

    // --- from the menu and the clock

    @Override
    @Transactional
    public void sellerChangedCategory(UUID dishId, UUID newCategoryId, UUID sellerUserId) {
        int changed = jdbc.sql("""
                update dish_category_cases set status = 'RESOLVED', final_category_id = :cat, decided_by_type = 'SELLER', decided_by_id = :who, decided_at = :now
                where dish_id = :d and status in ('RESTRICTED', 'AUTO_CORRECTED')""")
                .param("cat", newCategoryId).param("who", sellerUserId).param("now", Timestamp.from(clock.instant())).param("d", dishId).update();
        if (changed > 0) {
            dishes.setRestricted(dishId, false);
        }
    }

    /** One pass of the deadline job: cases past their fix deadline are corrected, corrections past their window expire. Returns how many moved. */
    @Transactional
    public int runDue(Instant now) {
        record Due(UUID id, UUID dishId, UUID vendorId, String dishName, UUID suggested) {
        }
        Duration window = Duration.ofDays(settings.getLong("appeal_window_days", 7));
        List<Due> due = jdbc.sql("select id, dish_id, vendor_id, dish_name, suggested_category_id from dish_category_cases where status = 'RESTRICTED' and deadline_at <= :now order by deadline_at, id")
                .param("now", Timestamp.from(now))
                .query((rs, n) -> new Due(rs.getObject("id", UUID.class), rs.getObject("dish_id", UUID.class), rs.getObject("vendor_id", UUID.class), rs.getString("dish_name"),
                        rs.getObject("suggested_category_id", UUID.class))).list();
        int moved = 0;
        for (Due d : due) {
            int changed = jdbc.sql("""
                    update dish_category_cases set status = 'AUTO_CORRECTED', final_category_id = suggested_category_id, appeal_deadline_at = :until, decided_by_type = 'SYSTEM', decided_at = :now
                    where id = :id and status = 'RESTRICTED'""")
                    .param("until", Timestamp.from(now.plus(window))).param("now", Timestamp.from(now)).param("id", d.id()).update();
            if (changed > 0) {
                dishes.setCategory(d.dishId(), d.suggested());
                dishes.setRestricted(d.dishId(), false);
                events.publishEvent(new DishCategoryCaseChanged(d.id(), d.vendorId(), d.dishId(), d.dishName(), "AUTO_CORRECTED", nameOf(d.suggested()), now.plus(window), null));
                moved++;
            }
        }
        moved += jdbc.sql("update dish_category_cases set status = 'EXPIRED', decided_by_type = 'SYSTEM', decided_at = :now where status = 'AUTO_CORRECTED' and appeal_deadline_at <= :now")
                .param("now", Timestamp.from(now)).update();
        return moved;
    }

    // --- internals

    private ModerationViews.Page page(String status, UUID vendorId, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
        if (status != null && !STATUSES.contains(status)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "`status` không hợp lệ.");
        }
        String where = " where (cast(:status as text) is null or c.status = :status) and (cast(:vendor as uuid) is null or c.vendor_id = :vendor)";
        long total = jdbc.sql("select count(*) from dish_category_cases c" + where).param("status", status).param("vendor", vendorId).query(Long.class).single();
        List<UUID> ids = jdbc.sql("select c.id from dish_category_cases c" + where + " order by " + ("APPEALED".equals(status) ? "c.appealed_at asc" : "c.opened_at desc") + ", c.id limit :limit offset :offset")
                .param("status", status).param("vendor", vendorId).param("limit", size).param("offset", (long) page * size).query(UUID.class).list();
        return new ModerationViews.Page(ids.stream().map(this::view).toList(), page, size, total);
    }

    private ModerationViews.Case view(UUID id) {
        return jdbc.sql("""
                select c.*, oc.name as original_name, sc.name as suggested_name, ac.name as appeal_name, fc.name as final_name
                from dish_category_cases c join categories oc on oc.id = c.original_category_id join categories sc on sc.id = c.suggested_category_id
                left join categories ac on ac.id = c.appeal_category_id left join categories fc on fc.id = c.final_category_id where c.id = :id""").param("id", id)
                .query((rs, n) -> new ModerationViews.Case(id, rs.getObject("dish_id", UUID.class), rs.getString("dish_name"), rs.getObject("vendor_id", UUID.class), rs.getString("shop_name"),
                        rs.getString("status"), rs.getString("reason"), new ModerationViews.CategoryRef(rs.getObject("original_category_id", UUID.class), rs.getString("original_name")),
                        new ModerationViews.CategoryRef(rs.getObject("suggested_category_id", UUID.class), rs.getString("suggested_name")),
                        ref(rs.getObject("appeal_category_id", UUID.class), rs.getString("appeal_name")), ref(rs.getObject("final_category_id", UUID.class), rs.getString("final_name")),
                        instant(rs.getTimestamp("deadline_at")), instant(rs.getTimestamp("appeal_deadline_at")), rs.getString("appeal_note"), instant(rs.getTimestamp("opened_at")),
                        instant(rs.getTimestamp("appealed_at")), instant(rs.getTimestamp("decided_at")), rs.getString("decided_by_type"), rs.getString("decision_reason")))
                .optional().orElseThrow(DishCategoryModerationService::notFound);
    }

    private UUID shopOf(CurrentPrincipal seller) {
        return dishes.operatingShopOf(seller.id()).orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SHOP_NOT_APPROVED", "Bạn chưa có cửa hàng được duyệt."));
    }

    /** Another shop's case is simply not found. */
    private void owned(UUID shop, UUID id) {
        if (jdbc.sql("select count(*) from dish_category_cases where id = :id and vendor_id = :v").param("id", id).param("v", shop).query(Long.class).single() == 0) {
            throw notFound();
        }
    }

    private void leaf(UUID categoryId) {
        if (categoryId == null || !catalog.isAssignableLeaf(categoryId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CATEGORY_NOT_ASSIGNABLE", "Chỉ chọn được ngành ở cấp lá đang hoạt động.");
        }
    }

    private String nameOf(UUID categoryId) {
        return categories.findById(categoryId).map(c -> c.getName()).orElse("");
    }

    private BusinessException notOpen(UUID id) {
        String status = jdbc.sql("select status from dish_category_cases where id = :id").param("id", id).query(String.class).optional().orElse("");
        return BusinessException.conflict("CASE_NOT_OPEN", "Vụ này không còn ở trạng thái cho phép làm việc đó (quá hạn, đã xử lý hoặc đã kháng nghị).").withProperty("status", status);
    }

    private BusinessException notAppealed(UUID id) {
        String status = jdbc.sql("select status from dish_category_cases where id = :id").param("id", id).query(String.class).optional().orElse("");
        return BusinessException.conflict("CASE_NOT_APPEALED", "Vụ này không có kháng nghị đang chờ quyết định.").withProperty("status", status);
    }

    private static ModerationViews.CategoryRef ref(UUID id, String name) {
        return id == null ? null : new ModerationViews.CategoryRef(id, name);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static BusinessException notFound() {
        return BusinessException.notFound("CASE_NOT_FOUND", "Không tìm thấy vụ này.");
    }
}
