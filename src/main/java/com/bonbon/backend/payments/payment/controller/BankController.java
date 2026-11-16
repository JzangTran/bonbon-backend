package com.bonbon.backend.payments.payment.controller;

import java.time.Duration;
import java.util.List;

import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.vietqr.VietQr;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The bank list the apps offer wherever a bank is chosen, so the name saved is one VietQR knows (payout, refund destination). */
@Tag(name = ApiTags.CUSTOMER_PAYMENTS)
@RestController
class BankController {

    @Schema(description = "Ngân hàng trong danh sách Napas mà VietQR dùng.")
    record BankView(@Schema(description = "Mã BIN 6 số.", example = "970436") String bin,
            @Schema(description = "Mã viết tắt.", example = "VCB") String code,
            @Schema(description = "Tên ngắn, dùng để lưu và hiển thị.", example = "Vietcombank") String shortName,
            @Schema(description = "Tên đầy đủ.", example = "Ngân hàng TMCP Ngoại Thương Việt Nam") String name) {
    }

    @Operation(operationId = "listBanks", summary = "Danh sách ngân hàng",
            description = "Không cần đăng nhập. Ứng dụng cho chọn từ danh sách này thay vì gõ tên ngân hàng; backend chỉ nhận tên có trong danh sách.")
    @GetMapping("/api/public/banks")
    ResponseEntity<List<BankView>> banks() {
        List<BankView> banks = VietQr.banks().stream().map(b -> new BankView(b.bin(), b.code(), b.shortName(), b.name())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic()).body(banks);
    }
}
