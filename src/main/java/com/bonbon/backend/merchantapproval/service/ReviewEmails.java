package com.bonbon.backend.merchantapproval.service;

import com.bonbon.backend.authentication.UserProfileService;
import com.bonbon.backend.common.mail.EmailSender;
import com.bonbon.backend.common.mail.EmailSender.EmailMessage;
import com.bonbon.backend.merchantapproval.ShopReviewed;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells the seller the outcome by email, after the decision is committed, so a seller who submitted the wizard
 * does not have to poll the status screen (approve-seller.md). Moves to the notification module when it exists.
 */
@Component
class ReviewEmails {

    private final EmailSender sender;
    private final UserProfileService profiles;
    private final String webUrl;

    ReviewEmails(EmailSender sender, UserProfileService profiles, @Value("${bonbon.app.web-url:http://localhost:5173}") String webUrl) {
        this.sender = sender;
        this.profiles = profiles;
        this.webUrl = webUrl;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onShopReviewed(ShopReviewed event) {
        UserProfileService.Profile owner = profiles.get(event.ownerUserId());
        String shop = event.shopName() == null ? "của bạn" : event.shopName();
        if (event.approved()) {
            sender.send(new EmailMessage(owner.email(), "Cửa hàng " + shop + " đã được duyệt",
                    "Chào " + owner.name() + ",\n\n"
                            + "Cửa hàng " + shop + " đã được duyệt trên bonbon. Hãy thêm thực đơn để khách trong khu bắt đầu đặt món:\n"
                            + webUrl + "/seller\n\nbonbon"));
        } else {
            sender.send(new EmailMessage(owner.email(), "Hồ sơ cửa hàng " + shop + " cần bổ sung",
                    "Chào " + owner.name() + ",\n\n"
                            + "Hồ sơ cửa hàng " + shop + " chưa được duyệt vì lý do sau:\n\n" + event.reason() + "\n\n"
                            + "Bạn có thể sửa hồ sơ và gửi lại tại:\n" + webUrl + "/seller\n\nbonbon"));
        }
    }
}
