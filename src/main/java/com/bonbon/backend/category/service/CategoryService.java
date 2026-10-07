package com.bonbon.backend.category.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.bonbon.backend.category.CategoryCatalog;
import com.bonbon.backend.category.CategoryCommissionChanged;
import com.bonbon.backend.category.CategoryUsage;
import com.bonbon.backend.category.dto.CategoryNode;
import com.bonbon.backend.category.dto.CategoryRequests;
import com.bonbon.backend.category.entity.Category;
import com.bonbon.backend.category.repository.CategoryRepository;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.persistence.ActorType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading and managing the category tree (flows/category/manage-categories.md). The tree is small (tens of
 * nodes), so it is loaded whole and assembled in memory.
 */
@Service
public class CategoryService implements CategoryCatalog {

    private static final UUID NO_ID = new UUID(0, 0);

    private final CategoryRepository categories;
    private final ObjectProvider<CategoryUsage> usage;
    private final ApplicationEventPublisher events;

    CategoryService(CategoryRepository categories, ObjectProvider<CategoryUsage> usage, ApplicationEventPublisher events) {
        this.categories = categories;
        this.usage = usage;
        this.events = events;
    }

    /** {@code includeHidden=false} is the public view: a hidden node disappears with its whole subtree. */
    @Transactional(readOnly = true)
    public List<CategoryNode> tree(boolean includeHidden) {
        List<Category> all = categories.findAllInTreeOrder();
        Map<UUID, List<Category>> byParent = all.stream().filter(c -> c.getParentId() != null)
                .collect(Collectors.groupingBy(Category::getParentId));
        return all.stream().filter(Category::isRoot).filter(c -> includeHidden || c.isActive())
                .map(root -> node(root, null, byParent, includeHidden)).toList();
    }

    @Transactional
    public CategoryNode create(CurrentPrincipal actor, CategoryRequests.Create req) {
        Category parent = find(req.parentId());
        if (parent.getLevel() >= Category.MAX_LEVEL) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CATEGORY_DEPTH_EXCEEDED",
                    "Ngành hàng chỉ có tối đa 3 cấp.");
        }
        if (dishCount(parent.getId()) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "CATEGORY_HAS_DISHES",
                    "Ngành này đang có món. Chuyển các món sang ngành khác trước khi thêm ngành con.");
        }
        String name = req.name().strip();
        requireUniqueName(parent.getId(), name, NO_ID);
        Category category = new Category(parent, name, categories.maxSortOrder(parent.getId()) + 1);
        category.setCommissionRate(req.commissionRate());
        category.actedBy(actor.actorType(), actor.id());
        CategoryNode created = save(category);
        if (req.commissionRate() != null) {
            events.publishEvent(new CategoryCommissionChanged(category.getId(), category.getName(), req.commissionRate(), null,
                    actor.actorType(), actor.id()));
        }
        return created;
    }

    @Transactional
    public CategoryNode update(CurrentPrincipal actor, UUID id, CategoryRequests.Update req) {
        Category category = find(id);
        boolean structural = req.name() != null || req.active() != null || req.parentId() != null || req.sortOrder() != null;
        if (category.isRoot() && structural) {
            throw rootFixed();
        }
        if (req.parentId() != null && !req.parentId().equals(category.getParentId())) {
            Category newParent = find(req.parentId());
            if (newParent.getLevel() != category.getLevel() - 1) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "CATEGORY_MOVE_INVALID",
                        "Chỉ chuyển được sang một ngành cha cùng cấp với ngành cha hiện tại.");
            }
            if (dishCount(newParent.getId()) > 0) {
                throw new BusinessException(HttpStatus.CONFLICT, "CATEGORY_HAS_DISHES",
                        "Ngành cha mới đang có món nên không nhận thêm ngành con.");
            }
            requireUniqueName(newParent.getId(), req.name() != null ? req.name().strip() : category.getName(), id);
            category.moveUnder(newParent);
        }
        if (req.name() != null) {
            String name = req.name().strip();
            requireUniqueName(category.getParentId(), name, id);
            category.setName(name);
        }
        BigDecimal before = category.getCommissionRate();
        if (Boolean.TRUE.equals(req.clearCommissionRate())) {
            category.setCommissionRate(null);
        } else if (req.commissionRate() != null) {
            category.setCommissionRate(req.commissionRate());
        }
        publishIfRateChanged(category, before, actor.actorType(), actor.id());
        if (req.active() != null) {
            category.setActive(req.active());
        }
        if (req.sortOrder() != null) {
            category.setSortOrder(req.sortOrder());
        }
        category.actedBy(actor.actorType(), actor.id());
        return save(category);
    }

    @Override
    @Transactional
    public void changeCommissionRate(UUID categoryId, BigDecimal rate, ActorType by, UUID actorId) {
        Category category = find(categoryId);
        BigDecimal before = category.getCommissionRate();
        category.setCommissionRate(rate);
        category.actedBy(by, actorId);
        categories.saveAndFlush(category);
        publishIfRateChanged(category, before, by, actorId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RateNode> rateNodes() {
        return categories.findAllInTreeOrder().stream()
                .map(c -> new RateNode(c.getId(), c.getParentId(), c.getLevel(), c.getName(), c.isActive(), c.getCommissionRate())).toList();
    }

    /** One history row per real change; saving the same rate again is not news. */
    private void publishIfRateChanged(Category category, BigDecimal before, ActorType by, UUID actorId) {
        BigDecimal after = category.getCommissionRate();
        boolean same = before == null ? after == null : after != null && before.compareTo(after) == 0;
        if (!same) {
            events.publishEvent(new CategoryCommissionChanged(category.getId(), category.getName(), after, before, by, actorId));
        }
    }

    /** Only an unused leaf can be deleted; anything a dish uses is hidden instead. */
    @Transactional
    public void delete(UUID id) {
        Category category = find(id);
        if (category.isRoot()) {
            throw rootFixed();
        }
        if (categories.existsByParentId(id)) {
            throw new BusinessException(HttpStatus.CONFLICT, "CATEGORY_HAS_CHILDREN",
                    "Hãy xoá hoặc chuyển các ngành con trước.");
        }
        if (dishCount(id) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "CATEGORY_IN_USE",
                    "Ngành đang có món nên không xoá được. Bạn có thể ẩn ngành này.");
        }
        categories.delete(category);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Set<UUID> subtreeIds(UUID categoryId) {
        Map<UUID, List<Category>> children = categories.findAll().stream().filter(c -> c.getParentId() != null)
                .collect(Collectors.groupingBy(Category::getParentId));
        java.util.Set<UUID> found = new java.util.LinkedHashSet<>();
        if (categories.existsById(categoryId)) {
            java.util.ArrayDeque<UUID> queue = new java.util.ArrayDeque<>(List.of(categoryId));
            while (!queue.isEmpty()) {
                UUID id = queue.poll();
                if (found.add(id)) {
                    children.getOrDefault(id, List.of()).forEach(c -> queue.add(c.getId()));
                }
            }
        }
        return found;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAssignableLeaf(UUID categoryId) {
        return categories.findById(categoryId)
                .filter(c -> c.getLevel() == Category.MAX_LEVEL && c.isActive() && ancestorsActive(c))
                .isPresent();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> effectiveCommissionRate(UUID categoryId) {
        Optional<Category> current = categories.findById(categoryId);
        while (current.isPresent()) {
            if (current.get().getCommissionRate() != null) {
                return Optional.of(current.get().getCommissionRate());
            }
            current = Optional.ofNullable(current.get().getParentId()).flatMap(categories::findById);
        }
        return Optional.empty();
    }

    private boolean ancestorsActive(Category category) {
        Optional<Category> parent = Optional.ofNullable(category.getParentId()).flatMap(categories::findById);
        while (parent.isPresent()) {
            if (!parent.get().isActive()) {
                return false;
            }
            parent = Optional.ofNullable(parent.get().getParentId()).flatMap(categories::findById);
        }
        return true;
    }

    private CategoryNode save(Category category) {
        try {
            categories.saveAndFlush(category);
        } catch (DataIntegrityViolationException e) {
            throw nameTaken();
        }
        Map<UUID, Category> all = categories.findAll().stream().collect(Collectors.toMap(Category::getId, Function.identity()));
        BigDecimal inherited = null;
        for (UUID p = category.getParentId(); p != null && inherited == null; p = all.get(p).getParentId()) {
            inherited = all.get(p).getCommissionRate();
        }
        Map<UUID, List<Category>> byParent = all.values().stream().filter(c -> c.getParentId() != null)
                .sorted(Comparator.comparingInt(Category::getSortOrder))
                .collect(Collectors.groupingBy(Category::getParentId));
        return node(category, inherited, byParent, true);
    }

    private CategoryNode node(Category c, BigDecimal inheritedRate, Map<UUID, List<Category>> byParent, boolean includeHidden) {
        BigDecimal effective = c.getCommissionRate() != null ? c.getCommissionRate() : inheritedRate;
        List<CategoryNode> children = new ArrayList<>();
        for (Category child : byParent.getOrDefault(c.getId(), List.of())) {
            if (includeHidden || child.isActive()) {
                children.add(node(child, effective, byParent, includeHidden));
            }
        }
        return new CategoryNode(c.getId(), c.getParentId(), c.getLevel(), c.getName(), c.getSortOrder(), c.isActive(),
                c.getCommissionRate(), effective, children);
    }

    private long dishCount(UUID categoryId) {
        return usage.orderedStream().mapToLong(u -> u.dishCount(categoryId)).sum();
    }

    private void requireUniqueName(UUID parentId, String name, UUID exceptId) {
        if (categories.siblingNameTaken(parentId, name, exceptId)) {
            throw nameTaken();
        }
    }

    private Category find(UUID id) {
        return categories.findById(id).orElseThrow(() -> BusinessException.notFound("CATEGORY_NOT_FOUND",
                "Không tìm thấy ngành hàng."));
    }

    private static BusinessException nameTaken() {
        return new BusinessException(HttpStatus.CONFLICT, "CATEGORY_NAME_TAKEN",
                "Đã có ngành cùng tên ở cấp này.");
    }

    private static BusinessException rootFixed() {
        return new BusinessException(HttpStatus.CONFLICT, "CATEGORY_ROOT_FIXED",
                "Ngành cấp 1 “Thực phẩm và đồ uống” là cố định; chỉ đặt được tỷ lệ hoa hồng.");
    }
}
