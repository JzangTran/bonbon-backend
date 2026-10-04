package com.bonbon.backend.merchant.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.merchant.OptionStatus;
import com.bonbon.backend.merchant.dto.OptionGroupsView;
import com.bonbon.backend.merchant.dto.OptionRequests;
import com.bonbon.backend.merchant.entity.Option;
import com.bonbon.backend.merchant.entity.OptionGroup;
import com.bonbon.backend.merchant.entity.Vendor;
import com.bonbon.backend.merchant.repository.OptionGroupRepository;
import com.bonbon.backend.merchant.repository.OptionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Option groups and their options (manage-menu-options.md): size, toppings and the like, owned by a shop and
 * attached to its dishes. Groups and options are archived, never deleted, so order snapshots stay valid.
 */
@Service
public class OptionService {

    private static final int MAX_OPTIONS_PER_GROUP = 30;

    private final ApprovedShops shops;
    private final OptionGroupRepository groups;
    private final OptionRepository options;
    private final JdbcClient jdbc;

    OptionService(ApprovedShops shops, OptionGroupRepository groups, OptionRepository options, JdbcClient jdbc) {
        this.shops = shops;
        this.groups = groups;
        this.options = options;
        this.jdbc = jdbc;
    }

    /** What the menu view needs per dish: its group ids in order, and which dishes cannot be built right now. */
    public record MenuOptionInfo(Map<UUID, List<UUID>> groupIdsByItem, Set<UUID> blockedItems) {
    }

    @Transactional(readOnly = true)
    public OptionGroupsView list(CurrentPrincipal owner) {
        return view(shops.of(owner.id()));
    }

    @Transactional
    public OptionGroupsView create(CurrentPrincipal owner, OptionRequests.Group req) {
        Vendor shop = shops.of(owner.id());
        validate(req);
        OptionGroup group = new OptionGroup(shop.getId(), req.name().strip(), req.min(), req.max());
        group.touchedBy(owner.actorType(), owner.id());
        groups.saveAndFlush(group);
        writeOptions(owner, group, req.options(), Map.of());
        return view(shop);
    }

    @Transactional
    public OptionGroupsView update(CurrentPrincipal owner, UUID groupId, OptionRequests.Group req) {
        Vendor shop = shops.of(owner.id());
        validate(req);
        OptionGroup group = group(shop, groupId);
        group.rules(req.name().strip(), req.min(), req.max());
        group.touchedBy(owner.actorType(), owner.id());
        groups.save(group);
        Map<UUID, Option> existing = options.findLive(List.of(group.getId())).stream()
                .collect(Collectors.toMap(Option::getId, Function.identity()));
        writeOptions(owner, group, req.options(), existing);
        return view(shop);
    }

    /** Detaches the group from every dish and archives it with its options. */
    @Transactional
    public OptionGroupsView delete(CurrentPrincipal owner, UUID groupId) {
        Vendor shop = shops.of(owner.id());
        OptionGroup group = group(shop, groupId);
        jdbc.sql("delete from menu_item_option_groups where group_id = :g").param("g", group.getId()).update();
        for (Option o : options.findLive(List.of(group.getId()))) {
            o.archive();
            o.touchedBy(owner.actorType(), owner.id());
        }
        group.archive();
        group.touchedBy(owner.actorType(), owner.id());
        groups.save(group);
        return view(shop);
    }

    /** The quick "out of pearls" switch; the group and the dish stay untouched. */
    @Transactional
    public OptionGroupsView setOptionStatus(CurrentPrincipal owner, UUID optionId, OptionStatus status) {
        Vendor shop = shops.of(owner.id());
        if (status == OptionStatus.ARCHIVED) {
            throw invalid("Chỉ có thể đặt trạng thái còn hàng hoặc hết hàng.");
        }
        Option option = options.findById(optionId)
                .filter(o -> o.getStatus() != OptionStatus.ARCHIVED)
                .filter(o -> groups.findActive(o.getGroupId(), shop.getId()).isPresent())
                .orElseThrow(() -> BusinessException.notFound("OPTION_NOT_FOUND", "Không tìm thấy lựa chọn."));
        option.setStatus(status);
        option.touchedBy(owner.actorType(), owner.id());
        options.save(option);
        return view(shop);
    }

    /** Replaces the groups a dish offers; the dish is already known to belong to {@code shop}. */
    @Transactional
    public void replaceItemGroups(Vendor shop, UUID itemId, List<UUID> groupIds) {
        if (new HashSet<>(groupIds).size() != groupIds.size()) {
            throw invalid("Mỗi nhóm lựa chọn chỉ được gắn một lần.");
        }
        for (UUID id : groupIds) {
            group(shop, id);
        }
        jdbc.sql("delete from menu_item_option_groups where menu_item_id = :i").param("i", itemId).update();
        int position = 1;
        for (UUID id : groupIds) {
            jdbc.sql("insert into menu_item_option_groups (menu_item_id, group_id, display_order) values (:i, :g, :o)")
                    .param("i", itemId).param("g", id).param("o", position++).update();
        }
    }

    @Transactional(readOnly = true)
    public MenuOptionInfo forMenu(UUID vendorId) {
        Map<UUID, List<UUID>> byItem = new LinkedHashMap<>();
        Set<UUID> blocked = new HashSet<>();
        jdbc.sql("""
                select l.menu_item_id, g.id as group_id, g.min_select,
                       (select count(*) from options o where o.group_id = g.id and o.status = 'AVAILABLE') as available
                from menu_item_option_groups l
                join option_groups g on g.id = l.group_id and g.status = 'ACTIVE'
                join menu_items i on i.id = l.menu_item_id
                where i.vendor_id = :v and i.archived_at is null
                order by l.display_order""").param("v", vendorId).query((rs, n) -> {
            UUID item = rs.getObject("menu_item_id", UUID.class);
            byItem.computeIfAbsent(item, k -> new ArrayList<>()).add(rs.getObject("group_id", UUID.class));
            // A required group without enough available options makes the dish impossible to order.
            if (rs.getInt("available") < rs.getInt("min_select")) {
                blocked.add(item);
            }
            return item;
        }).list();
        return new MenuOptionInfo(byItem, blocked);
    }

    // --- internals

    private OptionGroupsView view(Vendor shop) {
        List<OptionGroup> all = groups.findActiveByVendor(shop.getId());
        if (all.isEmpty()) {
            return new OptionGroupsView(List.of());
        }
        List<UUID> ids = all.stream().map(OptionGroup::getId).toList();
        Map<UUID, List<OptionGroupsView.Option>> optionsByGroup = options.findLive(ids).stream()
                .collect(Collectors.groupingBy(Option::getGroupId, LinkedHashMap::new, Collectors.mapping(
                        o -> new OptionGroupsView.Option(o.getId(), o.getName(), o.getPriceDelta(), o.getStatus(),
                                o.isDefaultChoice(), o.getDisplayOrder()), Collectors.toList())));
        Map<UUID, List<UUID>> itemsByGroup = new LinkedHashMap<>();
        jdbc.sql("""
                select l.group_id, l.menu_item_id from menu_item_option_groups l
                join menu_items i on i.id = l.menu_item_id
                where i.vendor_id = :v and i.archived_at is null
                order by i.sort_order, i.name""").param("v", shop.getId()).query((rs, n) -> {
            itemsByGroup.computeIfAbsent(rs.getObject("group_id", UUID.class), k -> new ArrayList<>())
                    .add(rs.getObject("menu_item_id", UUID.class));
            return null;
        }).list();
        return new OptionGroupsView(all.stream().map(g -> new OptionGroupsView.Group(g.getId(), g.getName(),
                g.getMinSelect(), g.getMaxSelect(), optionsByGroup.getOrDefault(g.getId(), List.of()),
                itemsByGroup.getOrDefault(g.getId(), List.of()))).toList());
    }

    private void writeOptions(CurrentPrincipal owner, OptionGroup group, List<OptionRequests.Option> requested,
            Map<UUID, Option> existing) {
        Set<UUID> kept = new HashSet<>();
        int position = 1;
        for (OptionRequests.Option req : requested) {
            Option option;
            if (req.id() == null) {
                option = new Option(group.getId());
            } else {
                option = existing.get(req.id());
                if (option == null || !kept.add(req.id())) {
                    throw BusinessException.notFound("OPTION_NOT_FOUND", "Không tìm thấy lựa chọn.");
                }
            }
            option.set(req.name().strip(), req.priceDelta(), req.status() == null ? OptionStatus.AVAILABLE : req.status(),
                    Boolean.TRUE.equals(req.defaultChoice()), position++);
            option.touchedBy(owner.actorType(), owner.id());
            options.save(option);
        }
        for (Option gone : existing.values()) {
            if (!kept.contains(gone.getId())) {
                gone.archive();
                gone.touchedBy(owner.actorType(), owner.id());
            }
        }
    }

    /** min <= max <= number of options, defaults fit in max, statuses are plain. */
    private static void validate(OptionRequests.Group req) {
        int count = req.options().size();
        if (count > MAX_OPTIONS_PER_GROUP) {
            throw invalid("Một nhóm có tối đa " + MAX_OPTIONS_PER_GROUP + " lựa chọn.");
        }
        if (req.min() > req.max() || req.max() > count) {
            throw invalid("Số lựa chọn tối thiểu phải nhỏ hơn hoặc bằng tối đa, và tối đa không vượt quá số lựa chọn có sẵn.");
        }
        if (req.max() < 1) {
            throw invalid("Số lựa chọn tối đa phải từ 1 trở lên.");
        }
        long defaults = req.options().stream().filter(o -> Boolean.TRUE.equals(o.defaultChoice())).count();
        if (defaults > req.max()) {
            throw invalid("Số lựa chọn mặc định vượt quá số lựa chọn tối đa.");
        }
        if (req.options().stream().anyMatch(o -> o.status() == OptionStatus.ARCHIVED)) {
            throw invalid("Chỉ có thể đặt trạng thái còn hàng hoặc hết hàng.");
        }
    }

    private OptionGroup group(Vendor shop, UUID id) {
        return groups.findActive(id, shop.getId())
                .orElseThrow(() -> BusinessException.notFound("OPTION_GROUP_NOT_FOUND", "Không tìm thấy nhóm lựa chọn."));
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "OPTION_GROUP_INVALID", message);
    }
}
