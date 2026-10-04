package com.bonbon.backend.merchant.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.bonbon.backend.category.CategoryCatalog;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.storage.ObjectStorage;
import com.bonbon.backend.common.storage.ObjectStorage.Visibility;
import com.bonbon.backend.common.storage.ValidatedFile;
import com.bonbon.backend.merchant.dto.MenuRequests;
import com.bonbon.backend.merchant.dto.MenuView;
import com.bonbon.backend.merchant.dto.OptionRequests;
import com.bonbon.backend.merchant.entity.MenuItem;
import com.bonbon.backend.merchant.entity.MenuSection;
import com.bonbon.backend.merchant.entity.Vendor;
import com.bonbon.backend.merchant.repository.MenuItemRepository;
import com.bonbon.backend.merchant.repository.MenuSectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * An approved shop managing its own menu sections and dishes (manage-menu.md, manage-menu-categories.md).
 * Every lookup is scoped to the caller's shop, so another shop's id is simply "not found".
 */
@Service
public class MenuService {

    private static final Logger log = LoggerFactory.getLogger(MenuService.class);
    private static final long PHOTO_MAX_BYTES = 5L * 1024 * 1024;

    private final ApprovedShops shops;
    private final OptionService optionService;
    private final MenuSectionRepository sections;
    private final MenuItemRepository items;
    private final CategoryCatalog categories;
    private final ObjectStorage storage;

    MenuService(ApprovedShops shops, OptionService optionService, MenuSectionRepository sections,
            MenuItemRepository items, CategoryCatalog categories, ObjectStorage storage) {
        this.shops = shops;
        this.optionService = optionService;
        this.sections = sections;
        this.items = items;
        this.categories = categories;
        this.storage = storage;
    }

    @Transactional(readOnly = true)
    public MenuView menu(CurrentPrincipal owner) {
        return view(approvedShop(owner.id()));
    }

    // --- sections

    @Transactional
    public MenuView createSection(CurrentPrincipal owner, MenuRequests.Section req) {
        Vendor shop = approvedShop(owner.id());
        String name = req.name().strip();
        if (sections.nameTaken(shop.getId(), name, new UUID(0, 0))) {
            throw sectionNameTaken();
        }
        MenuSection section = new MenuSection(shop.getId(), name, sections.maxSortOrder(shop.getId()) + 1);
        section.touchedBy(owner.actorType(), owner.id());
        saveSection(section);
        return view(shop);
    }

    @Transactional
    public MenuView renameSection(CurrentPrincipal owner, UUID sectionId, MenuRequests.Section req) {
        Vendor shop = approvedShop(owner.id());
        MenuSection section = section(shop, sectionId);
        String name = req.name().strip();
        if (sections.nameTaken(shop.getId(), name, section.getId())) {
            throw sectionNameTaken();
        }
        section.setName(name);
        section.touchedBy(owner.actorType(), owner.id());
        saveSection(section);
        return view(shop);
    }

    /** Blocked while the section still has dishes: the seller moves or removes them first. */
    @Transactional
    public MenuView deleteSection(CurrentPrincipal owner, UUID sectionId) {
        Vendor shop = approvedShop(owner.id());
        MenuSection section = section(shop, sectionId);
        if (items.sectionHasActiveItems(section.getId())) {
            throw BusinessException.conflict("SECTION_NOT_EMPTY", "Hãy chuyển hoặc xoá các món trong mục này trước.");
        }
        sections.delete(section);
        sections.flush();
        return view(shop);
    }

    @Transactional
    public MenuView reorderSections(CurrentPrincipal owner, MenuRequests.Order req) {
        Vendor shop = approvedShop(owner.id());
        List<MenuSection> all = sections.findByVendorIdOrderBySortOrderAscNameAsc(shop.getId());
        applyOrder(all.stream().collect(Collectors.toMap(MenuSection::getId, Function.identity())), req.ids(),
                (s, position) -> {
                    s.setSortOrder(position);
                    s.touchedBy(owner.actorType(), owner.id());
                });
        return view(shop);
    }

    // --- dishes

    @Transactional
    public MenuView createItem(CurrentPrincipal owner, MenuRequests.ItemCreate req) {
        Vendor shop = approvedShop(owner.id());
        MenuSection section = section(shop, req.sectionId());
        requireLeaf(req.categoryId());
        MenuItem item = new MenuItem(shop.getId(), section.getId(), req.categoryId(), req.name().strip(),
                blankToNull(req.description()), req.price(), req.stockQuantity(), items.maxSortOrder(section.getId()) + 1);
        item.touchedBy(owner.actorType(), owner.id());
        items.save(item);
        return view(shop);
    }

    @Transactional
    public MenuView updateItem(CurrentPrincipal owner, UUID itemId, MenuRequests.ItemUpdate req) {
        Vendor shop = approvedShop(owner.id());
        MenuItem item = item(shop, itemId);
        if (req.name() != null) {
            item.setName(req.name().strip());
        }
        if (req.price() != null) {
            item.setPrice(req.price());
        }
        if (Boolean.TRUE.equals(req.clearDescription())) {
            item.setDescription(null);
        } else if (req.description() != null) {
            item.setDescription(blankToNull(req.description()));
        }
        if (Boolean.TRUE.equals(req.clearStock())) {
            item.setStockQuantity(null);
        } else if (req.stockQuantity() != null) {
            item.setStockQuantity(req.stockQuantity());
        }
        if (req.sectionId() != null && !req.sectionId().equals(item.getSectionId())) {
            MenuSection target = section(shop, req.sectionId());
            item.setSectionId(target.getId());
            item.setSortOrder(items.maxSortOrder(target.getId()) + 1);
        }
        if (req.categoryId() != null && !req.categoryId().equals(item.getCategoryId())) {
            requireLeaf(req.categoryId());
            item.setCategoryId(req.categoryId());
        }
        item.touchedBy(owner.actorType(), owner.id());
        items.save(item);
        return view(shop);
    }

    /** Archives the dish: hidden from the menu, row kept for the orders that reference it. */
    @Transactional
    public MenuView deleteItem(CurrentPrincipal owner, UUID itemId) {
        Vendor shop = approvedShop(owner.id());
        MenuItem item = item(shop, itemId);
        item.archive(Instant.now());
        item.setSectionId(null);
        item.touchedBy(owner.actorType(), owner.id());
        items.save(item);
        return view(shop);
    }

    /** {@code ids} is the complete list of the section's dishes in the new order. */
    @Transactional
    public MenuView reorderItems(CurrentPrincipal owner, UUID sectionId, MenuRequests.Order req) {
        Vendor shop = approvedShop(owner.id());
        MenuSection section = section(shop, sectionId);
        Map<UUID, MenuItem> inSection = items.findActiveByVendor(shop.getId()).stream()
                .filter(i -> section.getId().equals(i.getSectionId()))
                .collect(Collectors.toMap(MenuItem::getId, Function.identity()));
        applyOrder(inSection, req.ids(), (i, position) -> {
            i.setSortOrder(position);
            i.touchedBy(owner.actorType(), owner.id());
        });
        return view(shop);
    }

    @Transactional
    public MenuView setPhoto(CurrentPrincipal owner, UUID itemId, MultipartFile upload) {
        Vendor shop = approvedShop(owner.id());
        MenuItem item = item(shop, itemId);
        ValidatedFile file = ValidatedFile.of(upload, ValidatedFile.IMAGES, PHOTO_MAX_BYTES);
        String key = storage.put(Visibility.PUBLIC, "menu-item/" + shop.getId(), file);
        deleteOnRollback(key);
        deleteAfterCommit(item.getPhotoKey());
        item.setPhotoKey(key);
        item.touchedBy(owner.actorType(), owner.id());
        items.save(item);
        return view(shop);
    }

    @Transactional
    public MenuView removePhoto(CurrentPrincipal owner, UUID itemId) {
        Vendor shop = approvedShop(owner.id());
        MenuItem item = item(shop, itemId);
        deleteAfterCommit(item.getPhotoKey());
        item.setPhotoKey(null);
        item.touchedBy(owner.actorType(), owner.id());
        items.save(item);
        return view(shop);
    }

    /** The quick sold-out switch, separate from full editing. Setting AVAILABLE does not restore stock. */
    @Transactional
    public MenuView setItemStatus(CurrentPrincipal owner, UUID itemId, MenuRequests.ItemStatus req) {
        Vendor shop = approvedShop(owner.id());
        MenuItem item = item(shop, itemId);
        item.setStatus(req.status());
        item.touchedBy(owner.actorType(), owner.id());
        items.save(item);
        return view(shop);
    }

    @Transactional
    public MenuView setItemOptionGroups(CurrentPrincipal owner, UUID itemId, OptionRequests.ItemGroups req) {
        Vendor shop = approvedShop(owner.id());
        MenuItem item = item(shop, itemId);
        optionService.replaceItemGroups(shop, item.getId(), req.groupIds());
        return view(shop);
    }

    // --- internals

    private MenuView view(Vendor shop) {
        OptionService.MenuOptionInfo optionInfo = optionService.forMenu(shop.getId());
        Map<UUID, List<MenuView.Item>> bySection = new LinkedHashMap<>();
        for (MenuItem i : items.findActiveByVendor(shop.getId())) {
            bySection.computeIfAbsent(i.getSectionId(), k -> new ArrayList<>()).add(new MenuView.Item(
                    i.getId(), i.getSectionId(), i.getCategoryId(), i.getName(), i.getDescription(), i.getPrice(),
                    i.getPhotoKey() == null ? null : storage.publicUrl(i.getPhotoKey()), i.getStatus(),
                    i.isSoldOut() || optionInfo.blockedItems().contains(i.getId()), i.getStockQuantity(), i.getSortOrder(),
                    optionInfo.groupIdsByItem().getOrDefault(i.getId(), List.of())));
        }
        return new MenuView(sections.findByVendorIdOrderBySortOrderAscNameAsc(shop.getId()).stream()
                .map(s -> new MenuView.Section(s.getId(), s.getName(), s.getSortOrder(),
                        bySection.getOrDefault(s.getId(), List.of())))
                .toList());
    }

    private Vendor approvedShop(UUID ownerId) {
        return shops.of(ownerId);
    }

    private MenuSection section(Vendor shop, UUID id) {
        return sections.findByIdAndVendorId(id, shop.getId())
                .orElseThrow(() -> BusinessException.notFound("MENU_SECTION_NOT_FOUND", "Không tìm thấy mục thực đơn."));
    }

    private MenuItem item(Vendor shop, UUID id) {
        return items.findActive(id, shop.getId())
                .orElseThrow(() -> BusinessException.notFound("MENU_ITEM_NOT_FOUND", "Không tìm thấy món."));
    }

    private void requireLeaf(UUID categoryId) {
        if (!categories.isAssignableLeaf(categoryId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CATEGORY_NOT_ASSIGNABLE",
                    "Hãy chọn một ngành hàng cấp cuối đang hoạt động.");
        }
    }

    private void saveSection(MenuSection section) {
        try {
            sections.saveAndFlush(section);
        } catch (DataIntegrityViolationException e) {
            throw sectionNameTaken();
        }
    }

    private static BusinessException sectionNameTaken() {
        return BusinessException.conflict("MENU_SECTION_NAME_TAKEN", "Đã có một mục thực đơn trùng tên.");
    }

    /** Positions follow {@code ids}; the list must name every current row exactly once. */
    private static <T> void applyOrder(Map<UUID, T> current, List<UUID> ids, java.util.function.ObjIntConsumer<T> assign) {
        Set<UUID> given = new HashSet<>(ids);
        if (given.size() != ids.size() || !given.equals(current.keySet())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MENU_ORDER_MISMATCH",
                    "Danh sách sắp xếp phải gồm đầy đủ các mục, mỗi mục một lần.");
        }
        int position = 1;
        for (UUID id : ids) {
            assign.accept(current.get(id), position++);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private void deleteAfterCommit(String key) {
        if (key == null) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    storage.delete(Visibility.PUBLIC, key);
                } catch (RuntimeException e) {
                    log.warn("Could not delete replaced photo {}", key, e);
                }
            }
        });
    }

    private void deleteOnRollback(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        storage.delete(Visibility.PUBLIC, key);
                    } catch (RuntimeException e) {
                        log.warn("Could not delete orphaned photo {}", key, e);
                    }
                }
            }
        });
    }
}
