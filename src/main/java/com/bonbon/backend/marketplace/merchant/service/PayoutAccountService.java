package com.bonbon.backend.marketplace.merchant.service;

import java.time.Duration;
import java.time.Instant;

import com.bonbon.backend.common.crypto.FieldCipher;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.marketplace.merchant.PayoutAccountChanged;
import com.bonbon.backend.marketplace.merchant.dto.ShopApplicationView;
import com.bonbon.backend.marketplace.merchant.dto.ShopProfileRequests;
import com.bonbon.backend.marketplace.merchant.entity.PayoutAccount;
import com.bonbon.backend.marketplace.merchant.entity.Vendor;
import com.bonbon.backend.marketplace.merchant.repository.PayoutAccountRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The shop changing where it is paid after approval: customers' VietQR transfers and the platform's payouts go to the new account
 * from now on. It is the most sensitive setting a shop has, so it needs a sign-in within the last ten minutes, only the shop account
 * itself may do it (never a main account or member), the old account stays as history, and the owner is told.
 */
@Service
public class PayoutAccountService {

    static final Duration RECENT = Duration.ofMinutes(10);

    private final ApprovedShops shops;
    private final PayoutAccountRepository payouts;
    private final FieldCipher cipher;
    private final ShopApplicationService views;
    private final ApplicationEventPublisher events;

    PayoutAccountService(ApprovedShops shops, PayoutAccountRepository payouts, FieldCipher cipher, ShopApplicationService views,
            ApplicationEventPublisher events) {
        this.shops = shops;
        this.payouts = payouts;
        this.cipher = cipher;
        this.views = views;
        this.events = events;
    }

    @Transactional
    public ShopApplicationView change(CurrentPrincipal owner, Instant authTime, ShopProfileRequests.PayoutAccount r, Instant now) {
        if (authTime == null || authTime.plus(RECENT).isBefore(now)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "REAUTH_REQUIRED", "Hãy đăng nhập lại trước khi đổi tài khoản nhận tiền (trong vòng 10 phút).");
        }
        Vendor vendor = shops.of(owner.id());
        String number = r.accountNumber().strip();
        payouts.findActive(vendor.getId()).ifPresent(old -> {
            if (r.bankName().strip().equals(old.getBankName()) && old.getAccountNumberEncrypted() != null
                    && number.equals(cipher.decrypt(old.getAccountNumberEncrypted()))) {
                throw BusinessException.conflict("SAME_PAYOUT_ACCOUNT", "Đây đã là tài khoản nhận tiền hiện tại.");
            }
            old.replace();
            payouts.saveAndFlush(old);
        });
        PayoutAccount account = new PayoutAccount(vendor.getId(), owner.actorType(), owner.id());
        account.update(r.bankName().strip(), r.accountHolderName().strip());
        account.setAccountNumber(cipher.encrypt(number), number.substring(number.length() - 4));
        payouts.save(account);
        events.publishEvent(new PayoutAccountChanged(vendor.getId(), r.bankName().strip(), number.substring(number.length() - 4)));
        return views.view(owner.id());
    }
}
