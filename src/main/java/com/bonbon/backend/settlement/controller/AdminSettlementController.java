package com.bonbon.backend.settlement.controller;

import java.time.LocalDate;
import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.settlement.dto.DebtRequests;
import com.bonbon.backend.settlement.dto.DebtViews;
import com.bonbon.backend.settlement.dto.SettlementRequests;
import com.bonbon.backend.settlement.dto.SettlementViews;
import com.bonbon.backend.settlement.service.CommissionDebtService;
import com.bonbon.backend.settlement.service.SettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.ADMIN_SETTLEMENT, description = "Đối soát tiền giữa nền tảng và từng quán: ai nợ ai, sổ cái từng quán, sao kê theo kỳ, ghi nhận tiền đã chuyển ngoài hệ thống. Mọi số liệu tính từ sổ cái nên luôn khớp nhau; sổ chỉ thêm dòng, không sửa hay xoá.")
@RestController
@RequestMapping("/api/admin/settlement")
@Validated
class AdminSettlementController {

    private final SettlementService settlement;
    private final CommissionDebtService debt;

    AdminSettlementController(SettlementService settlement, CommissionDebtService debt) {
        this.settlement = settlement;
        this.debt = debt;
    }

    @Operation(operationId = "getSettlementOverview", summary = "Tổng quan đối soát", description = "Số liệu toàn nền tảng (hoa hồng đã tính gồm phần ròng và VAT, tiền đang nợ các quán, tiền các quán nợ) và bảng các quán với số dư, số có thể chi trả, lần chi trả gần nhất, tỷ lệ hoa hồng thực tế và cờ `lowRate` khi thấp hơn hẳn mức chung (dấu hiệu xếp món vào ngành rẻ hơn). `status` lọc quán nền tảng nợ (`OWED_TO_SHOP`) hoặc nợ nền tảng (`OWED_BY_SHOP`).")
    @ApiError(status = 400, code = "INVALID_STATUS", when = "`status` không phải OWED_TO_SHOP hoặc OWED_BY_SHOP.")
    @ApiError(status = 400, code = "INVALID_SORT", when = "`sort` không phải balance_desc hoặc balance_asc.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–100.")
    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('settlement:read')")
    SettlementViews.Overview overview(@RequestParam(required = false) String status, @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return settlement.overview(status, sort, page, size);
    }

    @Operation(operationId = "getSettlementVendor", summary = "Số dư một quán", description = "Số dư, số có thể chi trả ngay, phần giữ cho khiếu nại đang mở (hiện luôn 0) và số quán đang nợ.")
    @ApiError(status = 404, code = "VENDOR_NOT_FOUND", when = "Không có quán với id này.")
    @GetMapping("/vendors/{id}")
    @PreAuthorize("hasAuthority('settlement:read')")
    SettlementViews.Vendor vendor(@PathVariable UUID id) {
        return settlement.vendor(id);
    }

    @Operation(operationId = "getSettlementLedger", summary = "Sổ cái của một quán", description = "Mới nhất trước, kèm mã đơn hiển thị của khoản thuộc đơn và số dư hiện tại. `type` lọc theo loại bút toán.")
    @ApiError(status = 404, code = "VENDOR_NOT_FOUND", when = "Không có quán với id này.")
    @ApiError(status = 400, code = "INVALID_TYPE", when = "`type` không phải một loại bút toán.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–100.")
    @GetMapping("/vendors/{id}/ledger")
    @PreAuthorize("hasAuthority('settlement:read')")
    SettlementViews.LedgerPage ledger(@PathVariable UUID id, @RequestParam(required = false) String type, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return settlement.ledger(id, type, page, size);
    }

    @Operation(operationId = "getSettlementStatement", summary = "Sao kê theo kỳ của một quán", description = "Theo ngày, tuần (từ thứ Hai) hoặc tháng theo giờ Việt Nam, mặc định 90 ngày gần nhất theo tuần: số đơn, giá trị món, giảm giá, phí giao, hoa hồng (đã gồm VAT, tách phần ròng và VAT), tiền đã chi trả, đã thu, điều chỉnh và số dư cuối kỳ. Kỳ không có bút toán được bỏ qua.")
    @ApiError(status = 404, code = "VENDOR_NOT_FOUND", when = "Không có quán với id này.")
    @ApiError(status = 400, code = "INVALID_GRANULARITY", when = "`granularity` không phải day, week hoặc month.")
    @ApiError(status = 400, code = "INVALID_RANGE", when = "`from` sau `to`, hoặc khoảng quá 2 năm.")
    @GetMapping("/vendors/{id}/statement")
    @PreAuthorize("hasAuthority('settlement:read')")
    SettlementViews.Statement statement(@PathVariable UUID id, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(required = false) String granularity) {
        return settlement.statement(id, from, to, granularity);
    }

    @Operation(operationId = "recordSettlementEntry", summary = "Ghi nhận chi trả, thu hoặc điều chỉnh", description = "Sau khi chuyển tiền ngoài hệ thống, ghi lại vào sổ. `PAYOUT`: tiền chuyển cho quán, không được vượt số có thể chi trả; cần mã giao dịch ngân hàng; quán được báo. `COLLECTION`: tiền nhận từ quán đang nợ hoa hồng, không vượt số nợ; cần mã giao dịch. `ADJUSTMENT`: điều chỉnh có dấu và bắt buộc có lý do (cách sửa một bút toán sai là điều chỉnh ngược). Header `Idempotency-Key` (8–100 ký tự) chống bấm hai lần: cùng khoá trả lại đúng bút toán đầu (200), lần đầu là 201. Bút toán không sửa hay xoá được.")
    @ApiError(status = 404, code = "VENDOR_NOT_FOUND", when = "Không có quán với id này.")
    @ApiError(status = 400, code = "AMOUNT_INVALID", when = "Số tiền không hợp lệ (PAYOUT và COLLECTION phải dương, ADJUSTMENT khác 0).")
    @ApiError(status = 400, code = "REFERENCE_REQUIRED", when = "PAYOUT hoặc COLLECTION thiếu mã giao dịch ngân hàng.")
    @ApiError(status = 400, code = "REASON_REQUIRED", when = "ADJUSTMENT thiếu lý do.")
    @ApiError(status = 409, code = "PAYOUT_EXCEEDS_PAYABLE", when = "Vượt số có thể chi trả; kèm `balance`, `heldForCases`, `payable`.")
    @ApiError(status = 409, code = "NOTHING_OWED", when = "COLLECTION cho quán không nợ nền tảng.")
    @ApiError(status = 409, code = "COLLECTION_EXCEEDS_DEBT", when = "COLLECTION vượt số quán đang nợ; kèm `owed`.")
    @ApiError(status = 409, code = "IDEMPOTENCY_KEY_REUSED", when = "Khoá đã dùng cho một khoản khác.")
    @ApiResponse(responseCode = "201", description = "Đã ghi bút toán mới.")
    @ApiResponse(responseCode = "200", description = "Khoá đã dùng với đúng yêu cầu này: trả lại bút toán đầu.")
    @PostMapping("/vendors/{id}/entries")
    @PreAuthorize("hasAuthority('settlement:write')")
    ResponseEntity<SettlementViews.Entry> record(CurrentPrincipal principal, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 8, max = 100) String idempotencyKey,
            @Valid @RequestBody SettlementRequests.Entry request) {
        SettlementService.Recorded recorded = settlement.record(principal, id, idempotencyKey, request);
        return ResponseEntity.status(recorded.created() ? HttpStatus.CREATED : HttpStatus.OK).body(recorded.entry());
    }

    @Operation(operationId = "getVendorCommissionStatements", summary = "Sao kê hoa hồng của một quán", description = "Các sao kê hoa hồng đã lập cho quán và tình trạng nợ hiện tại (giai đoạn `stage`, số quá hạn, các mốc hạn chế). Giai đoạn `REVIEW` nghĩa là nợ quá 30 ngày: nên xem xét khoá quán, nhưng hệ thống không bao giờ tự khoá.")
    @ApiError(status = 404, code = "VENDOR_NOT_FOUND", when = "Không có quán với id này.")
    @GetMapping("/vendors/{id}/commission-statements")
    @PreAuthorize("hasAuthority('settlement:read')")
    DebtViews.Statements commissionStatements(@PathVariable UUID id) {
        settlement.vendor(id);
        return debt.view(id);
    }

    @Operation(operationId = "extendCommissionStatement", summary = "Gia hạn sao kê hoa hồng", description = "Dời hạn trả của một sao kê chưa trả đủ, bắt buộc có lý do (được ghi lại cùng quản trị viên). Hạn mới phải sau hạn hiện tại và không quá 30 ngày kể từ bây giờ. Đồng hồ quá hạn tính lại từ hạn mới, nên các hạn chế do quá hạn được gỡ nếu sao kê không còn quá hạn.")
    @ApiError(status = 404, code = "STATEMENT_NOT_FOUND", when = "Không có sao kê với id này.")
    @ApiError(status = 409, code = "STATEMENT_PAID", when = "Sao kê đã trả đủ.")
    @ApiError(status = 400, code = "DUE_DATE_INVALID", when = "Hạn mới không sau hạn hiện tại.")
    @ApiError(status = 400, code = "DUE_DATE_TOO_FAR", when = "Hạn mới quá 30 ngày kể từ bây giờ.")
    @PostMapping("/statements/{id}/extend")
    @PreAuthorize("hasAuthority('settlement:write')")
    DebtViews.Statement extend(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody DebtRequests.Extend request) {
        return debt.extend(principal, id, request);
    }
}
