package com.bonbon.backend.marketplace.merchant.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.marketplace.merchant.ShopVouchers;
import com.bonbon.backend.marketplace.merchant.VendorStatus;
import com.bonbon.backend.marketplace.merchant.dto.VoucherViews;
import com.bonbon.backend.marketplace.merchant.entity.Vendor;
import com.bonbon.backend.marketplace.merchant.entity.Voucher;
import com.bonbon.backend.marketplace.merchant.repository.VendorRepository;
import com.bonbon.backend.marketplace.merchant.repository.VoucherRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A shop's vouchers (manage-vouchers.md): the seller's management, and what ordering asks of them. */
@Service
class VoucherService implements ShopVouchers, VoucherManagement {

    private final VoucherRepository vouchers;
    private final VendorRepository vendors;

    VoucherService(VoucherRepository vouchers, VendorRepository vendors) {
        this.vouchers = vouchers;
        this.vendors = vendors;
    }

    // --- the seller

    @Override
    @Transactional(readOnly = true)
    public List<VoucherViews.Item> list(CurrentPrincipal owner) {
        return vouchers.findByVendorIdOrderByCreatedAtDesc(approvedShop(owner.id()).getId()).stream().map(VoucherService::view).toList();
    }

    @Override
    @Transactional
    public VoucherViews.Item create(CurrentPrincipal owner, VoucherViews.Create req) {
        Vendor shop = approvedShop(owner.id());
        String code = req.code().strip().toUpperCase();
        if (vouchers.findByCode(shop.getId(), code).isPresent()) {
            throw BusinessException.conflict("VOUCHER_CODE_TAKEN", "Quán đã có mã " + code + ".");
        }
        Voucher v = new Voucher(shop.getId(), code);
        v.setDiscountType(req.discountType());
        v.setDiscountValue(req.discountValue());
        v.setMaxDiscount(req.maxDiscount());
        v.setMinOrderValue(req.minOrderValue());
        v.setWindow(req.validFrom(), req.validTo());
        v.setUsageLimit(req.usageLimit());
        v.setMaxPerCustomer(req.maxPerCustomer());
        requireConsistent(v);
        return view(vouchers.saveAndFlush(v));
    }

    @Override
    @Transactional
    public VoucherViews.Item update(CurrentPrincipal owner, UUID id, VoucherViews.Update req) {
        Voucher v = own(owner, id);
        if (req.discountType() != null) {
            v.setDiscountType(req.discountType());
        }
        if (req.discountValue() != null) {
            v.setDiscountValue(req.discountValue());
        }
        v.setMaxDiscount(Boolean.TRUE.equals(req.clearMaxDiscount()) ? null : firstNonNull(req.maxDiscount(), v.getMaxDiscount()));
        v.setMinOrderValue(Boolean.TRUE.equals(req.clearMinOrderValue()) ? null : firstNonNull(req.minOrderValue(), v.getMinOrderValue()));
        v.setUsageLimit(Boolean.TRUE.equals(req.clearUsageLimit()) ? null : firstNonNull(req.usageLimit(), v.getUsageLimit()));
        v.setMaxPerCustomer(Boolean.TRUE.equals(req.clearMaxPerCustomer()) ? null : firstNonNull(req.maxPerCustomer(), v.getMaxPerCustomer()));
        v.setWindow(req.validFrom() != null ? req.validFrom() : v.getValidFrom(), req.validTo() != null ? req.validTo() : v.getValidTo());
        requireConsistent(v);
        if (v.getUsageLimit() != null && v.getUsageLimit() < v.getUsedCount()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VOUCHER_LIMIT_BELOW_USED", "Giới hạn không thể thấp hơn số lượt đã dùng (" + v.getUsedCount() + ").");
        }
        return view(vouchers.saveAndFlush(v));
    }

    @Override
    @Transactional
    public VoucherViews.Item setStatus(CurrentPrincipal owner, UUID id, String status) {
        Voucher v = own(owner, id);
        v.setStatus(status);
        return view(vouchers.saveAndFlush(v));
    }

    @Override
    @Transactional
    public void disableAll(UUID vendorId) {
        vouchers.disableAll(vendorId);
    }

    // --- ordering

    @Override
    @Transactional(readOnly = true)
    public List<Offer> offers(UUID vendorId) {
        boolean selling = vendors.findById(vendorId).filter(v -> v.getStatus() == VendorStatus.APPROVED).isPresent();
        if (!selling) {
            return List.of();
        }
        return vouchers.findOffers(vendorId, Instant.now()).stream().map(v -> new Offer(v.getId(), v.getCode(), v.getDiscountType(),
                v.getDiscountValue(), v.getMaxDiscount(), v.getMinOrderValue(), v.getValidFrom(), v.getValidTo(), v.getMaxPerCustomer())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Quote quote(UUID vendorId, String code, int itemsTotal) {
        Voucher v = vouchers.findByCode(vendorId, code.strip())
                .orElseThrow(() -> BusinessException.notFound("VOUCHER_NOT_FOUND", "Mã ưu đãi không đúng."));
        if (!Voucher.ACTIVE.equals(v.getStatus()) || !v.inWindow(Instant.now())) {
            throw BusinessException.conflict("VOUCHER_NOT_ACTIVE", "Mã ưu đãi đã hết hạn hoặc chưa bắt đầu.");
        }
        if (v.usedUp()) {
            throw BusinessException.conflict("VOUCHER_USED_UP", "Mã ưu đãi đã hết lượt dùng.");
        }
        if (v.getMinOrderValue() != null && itemsTotal < v.getMinOrderValue()) {
            throw BusinessException.conflict("VOUCHER_MIN_ORDER", "Mã này áp dụng cho đơn từ " + v.getMinOrderValue() + " ₫.")
                    .withProperty("minOrderValue", v.getMinOrderValue());
        }
        return new Quote(v.getId(), v.discountOn(itemsTotal), v.getMaxPerCustomer());
    }

    @Override
    @Transactional
    public boolean reserve(UUID voucherId) {
        return vouchers.takeUse(voucherId) == 1;
    }

    @Override
    @Transactional
    public void release(UUID voucherId) {
        vouchers.giveUseBack(voucherId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> codeOf(UUID voucherId) {
        return vouchers.findById(voucherId).map(Voucher::getCode);
    }

    // --- internals

    private static void requireConsistent(Voucher v) {
        if (!v.getValidTo().isAfter(v.getValidFrom())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VOUCHER_WINDOW_INVALID", "Ngày kết thúc phải sau ngày bắt đầu.");
        }
        if (Voucher.PERCENT.equals(v.getDiscountType()) && v.getDiscountValue() > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VOUCHER_PERCENT_INVALID", "Mức giảm phần trăm tối đa là 100%.");
        }
    }

    private static Integer firstNonNull(Integer a, Integer b) {
        return a != null ? a : b;
    }

    private Voucher own(CurrentPrincipal owner, UUID id) {
        Vendor shop = approvedShop(owner.id());
        return vouchers.findById(id).filter(v -> v.getVendorId().equals(shop.getId()))
                .orElseThrow(() -> BusinessException.notFound("VOUCHER_NOT_FOUND", "Không tìm thấy voucher."));
    }

    private Vendor approvedShop(UUID ownerId) {
        return vendors.findByOwnerUserId(ownerId).filter(v -> v.getStatus() == VendorStatus.APPROVED)
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SHOP_NOT_APPROVED", "Chỉ cửa hàng đã được duyệt mới có voucher."));
    }

    private static VoucherViews.Item view(Voucher v) {
        return new VoucherViews.Item(v.getId(), v.getCode(), v.getDiscountType(), v.getDiscountValue(), v.getMaxDiscount(), v.getMinOrderValue(),
                v.getValidFrom(), v.getValidTo(), v.getUsageLimit(), v.getUsedCount(), v.getMaxPerCustomer(), v.getStatus());
    }
}
