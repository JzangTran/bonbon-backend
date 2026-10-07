package com.bonbon.backend.merchant.controller;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.web.ClientContext;
import com.bonbon.backend.merchant.dto.ShopApplicationRequests;
import com.bonbon.backend.merchant.dto.ShopApplicationView;
import com.bonbon.backend.merchant.service.ShopApplicationService;
import com.bonbon.backend.merchant.service.ShopApplicationService.FileKind;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The seller's own shop application (open-shop.md). Steps are idempotent draft saves; the shop is always the
 * caller's own, so no id appears in the URL.
 */
@Tag(name = ApiTags.SELLER_OPEN_SHOP, description = "Hồ sơ mở cửa hàng 4 bước có lưu nháp, rồi gửi duyệt. Luôn là hồ sơ của chính người gọi.")
@RestController
@RequestMapping("/api/merchant/shop")
@PreAuthorize("hasAuthority('vendor:create')")
class ShopApplicationController {

    private final ShopApplicationService applications;

    ShopApplicationController(ShopApplicationService applications) {
        this.applications = applications;
    }

    /** Status NONE before the first save; otherwise the application with each step's completeness. */
    @Operation(operationId = "getMyShopApplication", summary = "Xem hồ sơ cửa hàng", description = "`status` là `NONE` trước lần lưu đầu; ngoài ra là hồ sơ kèm mức hoàn thành từng bước. Giấy tờ là liên kết ký tạm.")
    @GetMapping
    ShopApplicationView current(CurrentPrincipal principal) {
        return applications.view(principal.id());
    }

    @Operation(operationId = "saveShopInfo", summary = "Bước 1: thông tin quán", description = "Tên, số điện thoại, email và địa chỉ (`placeId` từ gợi ý địa chỉ).")
    @ApiError(status = 409, code = "SHOP_NOT_EDITABLE", when = "Hồ sơ đang chờ duyệt hoặc đã duyệt, không sửa ở đây được.")
    @ApiError(status = 400, code = "ADDRESS_NOT_FOUND", when = "`placeId` không tra được ra địa chỉ.")
    @ApiError(status = 503, code = "GEOCODING_UNAVAILABLE", when = "Dịch vụ tra địa chỉ (Goong) tạm thời không phản hồi; thử lại sau.")
    @PutMapping("/steps/1")
    ShopApplicationView shopInfo(CurrentPrincipal principal, @Valid @RequestBody ShopApplicationRequests.Step1 request) {
        return applications.saveShopInfo(principal, request);
    }

    @Operation(operationId = "saveShipping", summary = "Bước 2: giờ mở cửa và giao hàng", description = "Lịch mở cửa theo tuần, bán kính giao (không vượt mức tối đa của nền tảng), phí giao, ngưỡng miễn phí, đơn tối thiểu.")
    @ApiError(status = 409, code = "SHOP_NOT_EDITABLE", when = "Hồ sơ không sửa được ở trạng thái này.")
    @ApiError(status = 400, code = "DELIVERY_RADIUS_TOO_LARGE", when = "Vượt bán kính giao tối đa; có `maxDeliveryRadiusKm`.")
    @ApiError(status = 400, code = "OPENING_HOURS_INVALID", when = "Khung giờ không hợp lệ.")
    @ApiError(status = 400, code = "OPENING_HOURS_OVERLAP", when = "Các khung giờ chồng nhau.")
    @ApiError(status = 400, code = "OPENING_HOURS_TOO_MANY", when = "Quá nhiều khung giờ trong một ngày.")
    @PutMapping("/steps/2")
    ShopApplicationView shipping(CurrentPrincipal principal, @Valid @RequestBody ShopApplicationRequests.Step2 request) {
        return applications.saveShipping(principal, request);
    }

    @Operation(operationId = "saveTaxAndPayout", summary = "Bước 3: thuế và tài khoản nhận tiền", description = "Loại hình kinh doanh, mã số thuế, email hoá đơn và tài khoản ngân hàng (lưu mã hoá, trả về dạng che).")
    @ApiError(status = 409, code = "SHOP_NOT_EDITABLE", when = "Hồ sơ không sửa được ở trạng thái này.")
    @PutMapping("/steps/3")
    ShopApplicationView taxAndPayout(CurrentPrincipal principal, @Valid @RequestBody ShopApplicationRequests.Step3 request) {
        return applications.saveTaxAndPayout(principal, request);
    }

    @Operation(operationId = "saveIdentity", summary = "Bước 4: định danh", description = "Loại và số giấy tờ, họ tên, đồng ý xử lý dữ liệu định danh. Ảnh giấy tờ tải riêng.")
    @ApiError(status = 409, code = "SHOP_NOT_EDITABLE", when = "Hồ sơ không sửa được ở trạng thái này.")
    @ApiError(status = 400, code = "DOC_NUMBER_INVALID", when = "Số giấy tờ không đúng định dạng của loại giấy tờ.")
    @ApiError(status = 400, code = "CONSENT_REQUIRED", when = "Chưa đồng ý đủ các văn bản bắt buộc (điều khoản của vai trò và chính sách bảo mật).")
    @ApiError(status = 409, code = "LEGAL_DOCUMENTS_CHANGED", when = "Văn bản vừa có phiên bản mới; tải lại và đồng ý lại.")
    @PutMapping("/steps/4")
    ShopApplicationView identity(CurrentPrincipal principal, @Valid @RequestBody ShopApplicationRequests.Step4 request,
            HttpServletRequest http) {
        return applications.saveIdentity(principal, request, ClientContext.from(http));
    }

    /** {@code kind}: business-license (image or PDF), identity-front, identity-selfie (images). */
    @Operation(operationId = "uploadShopFile", summary = "Tải giấy tờ", description = "`kind`: `business-license` (ảnh hoặc PDF), `identity-front`, `identity-selfie` (ảnh). Lưu riêng tư, thay tệp cũ.")
    @ApiError(status = 404, code = "FILE_KIND_UNKNOWN", when = "`kind` không thuộc ba loại trên.")
    @ApiError(status = 409, code = "SHOP_NOT_EDITABLE", when = "Hồ sơ không sửa được ở trạng thái này.")
    @ApiError(status = 400, code = "FILE_REQUIRED", when = "Không có tệp trong trường `file`.")
    @ApiError(status = 400, code = "FILE_UNREADABLE", when = "Không đọc được tệp.")
    @ApiError(status = 413, code = "FILE_TOO_LARGE", when = "Tệp vượt giới hạn dung lượng.")
    @ApiError(status = 415, code = "UNSUPPORTED_FILE_TYPE", when = "Định dạng không được nhận (kiểm tra theo nội dung tệp, không theo tên).")
    @PostMapping(path = "/files/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ShopApplicationView upload(CurrentPrincipal principal, @PathVariable String kind, @RequestPart("file") MultipartFile file) {
        return applications.uploadFile(principal, fileKind(kind), file);
    }

    @Operation(operationId = "submitShopApplication", summary = "Gửi duyệt", description = "Kiểm tra đủ 4 bước rồi chuyển sang chờ duyệt; bị từ chối thì sửa và gửi lại.")
    @ApiError(status = 400, code = "SHOP_APPLICATION_INCOMPLETE", when = "Còn bước chưa đủ; có `firstIncompleteStep`.")
    @ApiError(status = 409, code = "SHOP_NOT_EDITABLE", when = "Hồ sơ không gửi được ở trạng thái này.")
    @PostMapping("/submit")
    ShopApplicationView submit(CurrentPrincipal principal) {
        return applications.submit(principal);
    }

    private static FileKind fileKind(String kind) {
        return switch (kind) {
            case "business-license" -> FileKind.BUSINESS_LICENSE;
            case "identity-front" -> FileKind.IDENTITY_FRONT;
            case "identity-selfie" -> FileKind.IDENTITY_SELFIE;
            default -> throw BusinessException.notFound("FILE_KIND_UNKNOWN", "Loại tệp không hợp lệ.");
        };
    }
}
