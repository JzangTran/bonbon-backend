package com.bonbon.backend.shopperformance.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.shopperformance.dto.CaseRequests;
import com.bonbon.backend.shopperformance.dto.CaseViews;
import com.bonbon.backend.shopperformance.service.NoShowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = ApiTags.SELLER_ORDER_CASES, description = "Khiếu nại của khách về đơn đã giao. Quán có 12 giờ để chấp nhận (khách được hoàn tiền và quán chịu khoản đó) hoặc phản đối (quản trị viên quyết định). Không trả lời kịp cũng chuyển cho quản trị viên. Chỉ thấy khiếu nại của chính cửa hàng mình.")
@RestController
@RequestMapping("/api/merchant/orders/{id}")
@PreAuthorize("hasAuthority('order:write')")
@Validated
class MerchantNoShowController {

    private final NoShowService noShows;

    MerchantNoShowController(NoShowService noShows) {
        this.noShows = noShows;
    }

    @Operation(operationId = "reportCustomerNoShow", summary = "Báo khách vắng mặt", description = "Khi đơn đang giao và đã quá thời gian chờ tối thiểu (10 phút kể từ lúc giao đi), quán báo không liên lạc được với khách kèm ghi chú đã làm gì (gọi, gõ cửa, chờ bao lâu) và một ảnh nếu có. Đơn được giữ lại, không tự chuyển sang đã giao sau 3 giờ. Khách có 2 giờ để trả lời; không trả lời thì quản trị viên quyết, và im lặng không bị coi là thừa nhận. Không có khoản tiền nào bị đòi trong báo cáo này.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @ApiError(status = 404, code = "ORDER_NOT_FOUND", when = "Đơn không tồn tại hoặc không phải của cửa hàng người gọi.")
    @ApiError(status = 409, code = "ORDER_NOT_OUT_FOR_DELIVERY", when = "Đơn không còn ở trạng thái đang giao; kèm `status`.")
    @ApiError(status = 409, code = "NO_SHOW_TOO_EARLY", when = "Chưa đủ thời gian chờ tối thiểu; kèm `availableAt`.")
    @ApiError(status = 409, code = "CASE_ALREADY_FILED", when = "Đơn đã có báo cáo khách vắng mặt.")
    @ApiError(status = 409, code = "ORDER_ALREADY_CHANGED", when = "Đơn vừa đổi trạng thái (ví dụ khách vừa bấm đã nhận); tải lại để xem.")
    @ApiError(status = 400, code = "PHOTO_INVALID", when = "Khoá ảnh không thuộc đơn này.")
    @ApiResponse(responseCode = "201", description = "Đã tạo báo cáo.")
    @PostMapping("/no-show")
    @ResponseStatus(HttpStatus.CREATED)
    CaseViews.Case report(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody CaseRequests.NoShow request) {
        return noShows.file(principal, id, request.note(), request.photoKey());
    }

    @Operation(operationId = "uploadNoShowPhoto", summary = "Tải ảnh cho báo cáo khách vắng mặt", description = "Tải một ảnh (JPEG, PNG hoặc WEBP, tối đa 5 MB) trước khi gửi báo cáo; ảnh được lưu riêng tư. Gửi `photoKey` trong báo cáo. Giới hạn 5 ảnh mỗi giờ cho mỗi đơn.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @ApiError(status = 404, code = "ORDER_NOT_FOUND", when = "Đơn không tồn tại hoặc không phải của cửa hàng người gọi.")
    @ApiError(status = 409, code = "ORDER_NOT_OUT_FOR_DELIVERY", when = "Đơn không còn ở trạng thái đang giao; kèm `status`.")
    @ApiError(status = 413, code = "FILE_TOO_LARGE", when = "Ảnh vượt 5 MB.")
    @ApiError(status = 415, code = "UNSUPPORTED_FILE_TYPE", when = "Định dạng không được nhận (kiểm tra theo nội dung tệp).")
    @ApiError(status = 429, code = "TOO_MANY_UPLOADS", when = "Tải quá 5 ảnh trong một giờ cho đơn này.")
    @PostMapping(path = "/no-show-photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    CaseViews.Upload photo(CurrentPrincipal principal, @PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return noShows.uploadPhoto(principal, id, file);
    }
}
