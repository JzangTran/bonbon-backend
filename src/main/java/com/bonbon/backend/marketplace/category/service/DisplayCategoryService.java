package com.bonbon.backend.marketplace.category.service;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.storage.ObjectStorage;
import com.bonbon.backend.common.storage.ObjectStorage.Visibility;
import com.bonbon.backend.common.storage.ValidatedFile;
import com.bonbon.backend.marketplace.category.dto.DisplayCategoryViews;
import com.bonbon.backend.marketplace.category.entity.Category;
import com.bonbon.backend.marketplace.category.entity.DisplayCategory;
import com.bonbon.backend.marketplace.category.repository.CategoryRepository;
import com.bonbon.backend.marketplace.category.repository.DisplayCategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * The picture strip of the customer home page (owner decision 2026-11-03): administrators name a display category, give it a
 * picture and the leaf categories it gathers; the app filters shops by those leaves.
 */
@Service
public class DisplayCategoryService {

    private static final Logger log = LoggerFactory.getLogger(DisplayCategoryService.class);
    private static final long IMAGE_MAX_BYTES = 2L * 1024 * 1024;

    private final DisplayCategoryRepository displays;
    private final CategoryRepository categories;
    private final ObjectStorage storage;

    DisplayCategoryService(DisplayCategoryRepository displays, CategoryRepository categories, ObjectStorage storage) {
        this.displays = displays;
        this.categories = categories;
        this.storage = storage;
    }

    /** What the app shows: active ones, in the administrator's order. */
    @Transactional(readOnly = true)
    public List<DisplayCategoryViews.Item> visible() {
        return displays.findByActiveTrueOrderBySortOrderAscNameAsc().stream()
                .map(d -> new DisplayCategoryViews.Item(d.getId(), d.getName(), imageUrl(d)))
                .toList();
    }

    /** The leaf categories a display category gathers; empty when it does not exist or is hidden (the filter then matches nothing). */
    @Transactional(readOnly = true)
    public Set<UUID> leavesOf(UUID displayCategoryId) {
        return displays.findById(displayCategoryId).filter(DisplayCategory::isActive)
                .map(d -> Set.copyOf(d.getLeafIds())).orElse(Set.of());
    }

    @Transactional(readOnly = true)
    public List<DisplayCategoryViews.Admin> all() {
        List<DisplayCategory> list = displays.findAllByOrderBySortOrderAscNameAsc();
        Map<UUID, Category> names = byId(list.stream().flatMap(d -> d.getLeafIds().stream()).collect(Collectors.toSet()));
        return list.stream().map(d -> admin(d, names)).toList();
    }

    @Transactional
    public DisplayCategoryViews.Admin create(DisplayCategoryViews.Create req) {
        DisplayCategory d = new DisplayCategory(req.name().strip(), displays.maxSortOrder() + 1);
        d.replaceLeafIds(requireLeaves(req.leafIds()));
        return admin(displays.save(d));
    }

    @Transactional
    public DisplayCategoryViews.Admin update(UUID id, DisplayCategoryViews.Update req) {
        DisplayCategory d = find(id);
        if (req.name() != null) {
            d.setName(req.name().strip());
        }
        if (req.leafIds() != null) {
            d.replaceLeafIds(requireLeaves(req.leafIds()));
        }
        if (req.sortOrder() != null) {
            d.setSortOrder(req.sortOrder());
        }
        if (req.active() != null) {
            d.setActive(req.active());
        }
        return admin(displays.save(d));
    }

    @Transactional
    public void delete(UUID id) {
        DisplayCategory d = find(id);
        String image = d.getImageKey();
        displays.delete(d);
        deleteAfterCommit(image);
    }

    @Transactional
    public DisplayCategoryViews.Admin setImage(UUID id, MultipartFile upload) {
        DisplayCategory d = find(id);
        ValidatedFile file = ValidatedFile.of(upload, ValidatedFile.IMAGES, IMAGE_MAX_BYTES);
        String key = storage.put(Visibility.PUBLIC, "display-category/" + d.getId(), file);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    deleteQuietly(key);
                }
            }
        });
        deleteAfterCommit(d.getImageKey());
        d.setImageKey(key);
        return admin(displays.save(d));
    }

    @Transactional
    public DisplayCategoryViews.Admin removeImage(UUID id) {
        DisplayCategory d = find(id);
        deleteAfterCommit(d.getImageKey());
        d.setImageKey(null);
        return admin(displays.save(d));
    }

    private DisplayCategory find(UUID id) {
        return displays.findById(id)
                .orElseThrow(() -> BusinessException.notFound("DISPLAY_CATEGORY_NOT_FOUND", "Không tìm thấy danh mục hiển thị."));
    }

    /** Only level-3 categories hold dishes, so only they can be gathered. */
    private Set<UUID> requireLeaves(Set<UUID> ids) {
        List<Category> found = categories.findAllById(ids);
        if (found.size() != ids.size() || found.stream().anyMatch(c -> c.getLevel() != Category.MAX_LEVEL)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DISPLAY_CATEGORY_LEAF_INVALID",
                    "Chỉ chọn được ngành cấp 3 đang có trong cây ngành hàng.");
        }
        return new LinkedHashSet<>(ids);
    }

    private Map<UUID, Category> byId(Set<UUID> ids) {
        return categories.findAllById(ids).stream().collect(Collectors.toMap(Category::getId, Function.identity()));
    }

    private DisplayCategoryViews.Admin admin(DisplayCategory d) {
        return admin(d, byId(d.getLeafIds()));
    }

    private DisplayCategoryViews.Admin admin(DisplayCategory d, Map<UUID, Category> names) {
        List<DisplayCategoryViews.Leaf> leaves = d.getLeafIds().stream().map(names::get).filter(Objects::nonNull)
                .sorted(Comparator.comparing(Category::getName, String.CASE_INSENSITIVE_ORDER))
                .map(c -> new DisplayCategoryViews.Leaf(c.getId(), c.getName())).toList();
        return new DisplayCategoryViews.Admin(d.getId(), d.getName(), imageUrl(d), d.getSortOrder(), d.isActive(), leaves);
    }

    private String imageUrl(DisplayCategory d) {
        return d.getImageKey() == null ? null : storage.publicUrl(d.getImageKey());
    }

    private void deleteAfterCommit(String key) {
        if (key == null) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteQuietly(key);
            }
        });
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(Visibility.PUBLIC, key);
        } catch (RuntimeException e) {
            log.warn("Could not delete display category picture {}", key, e);
        }
    }
}
