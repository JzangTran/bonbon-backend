package com.bonbon.backend.shopperformance.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.shopperformance.dto.CaseRequests;
import com.bonbon.backend.shopperformance.dto.CaseViews;
import com.bonbon.backend.shopperformance.service.OrderCaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = ApiTags.CUSTOMER_ORDER_CASES, description = "Khách báo vấn đề với đơn đã giao trong 24 giờ: chưa nhận được hàng, thiếu món, sai món hoặc chất lượng. Quán trả lời trong 12 giờ; nếu hai bên không đồng ý, quản trị viên quyết. Mỗi đơn một khiếu nại. Trong lúc chờ, phần tiền quán sẽ phải chịu được giữ lại khỏi số tiền có thể chi trả.")
@RestController
@RequestMapping("/api/orders/{id}")
@PreAuthorize("hasAuthority('order:report')")
@Validated
class CustomerOrderCaseController {

    private final OrderCaseService cases;

    CustomerOrderCaseController(OrderCaseService cases) {
        this.cases = cases;
    }

    @Operation(operationId = "reportOrderNotReceived", summary = "Báo chưa nhận được đơn", description = "Cho đơn đã giao trong vòng 24 giờ kể từ khi giao và khách chưa tự xác nhận đã nhận. Hoàn lại toàn bộ số đã trả, gồm phí giao, nếu khiếu nại được chấp nhận. Quán được báo và có 12 giờ để trả lời.")
    @ApiError(status = 404, code = "ORDER_NOT_FOUND", when = "Đơn không tồn tại hoặc không phải của người gọi.")
    @ApiError(status = 409, code = "ORDER_NOT_DELIVERED", when = "Đơn chưa ở trạng thái đã giao.")
    @ApiError(status = 409, code = "REPORT_WINDOW_CLOSED", when = "Đã quá 24 giờ kể từ khi đơn được giao.")
    @ApiError(status = 409, code = "ALREADY_CONFIRMED_RECEIVED", when = "Khách đã tự xác nhận nhận được đơn này.")
    @ApiError(status = 409, code = "CASE_ALREADY_FILED", when = "Đơn đã có khiếu nại; kèm `caseId` để xem.")
    @ApiResponse(responseCode = "201", description = "Đã tạo khiếu nại.")
    @PostMapping("/report-not-received")
    @ResponseStatus(HttpStatus.CREATED)
    CaseViews.Case notReceived(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody(required = false) CaseRequests.NotReceived request) {
        return cases.reportNotReceived(principal.id(), id, request);
    }

    @Operation(operationId = "reportOrderIncident", summary = "Báo thiếu món, sai món hoặc chất lượng", description = "Cho đơn đã giao trong vòng 24 giờ. Chọn các dòng món bị ảnh hưởng và số phần; tiền hoàn mỗi dòng là số khách thực trả cho dòng đó (đã trừ phần giảm giá, gồm lựa chọn thêm) theo tỷ lệ số phần, không gồm phí giao. Cần ít nhất một ảnh (đã tải lên qua `case-photos`) với `WRONG_ITEM` và `QUALITY`, tối đa 3 ảnh. Việc xác nhận đã nhận không chặn báo cáo này.")
    @ApiError(status = 404, code = "ORDER_NOT_FOUND", when = "Đơn không tồn tại hoặc không phải của người gọi.")
    @ApiError(status = 409, code = "ORDER_NOT_DELIVERED", when = "Đơn chưa ở trạng thái đã giao.")
    @ApiError(status = 409, code = "REPORT_WINDOW_CLOSED", when = "Đã quá 24 giờ kể từ khi đơn được giao.")
    @ApiError(status = 409, code = "CASE_ALREADY_FILED", when = "Đơn đã có khiếu nại; kèm `caseId` để xem.")
    @ApiError(status = 400, code = "LINE_NOT_IN_ORDER", when = "Có món không thuộc đơn này.")
    @ApiError(status = 400, code = "LINE_REPEATED", when = "Một món được chọn nhiều lần.")
    @ApiError(status = 400, code = "QUANTITY_INVALID", when = "Số phần ngoài khoảng từ 1 đến số đã đặt.")
    @ApiError(status = 400, code = "PHOTO_REQUIRED", when = "Thiếu ảnh cho `WRONG_ITEM` hoặc `QUALITY`.")
    @ApiError(status = 400, code = "TOO_MANY_PHOTOS", when = "Quá số ảnh cho phép (3).")
    @ApiError(status = 400, code = "PHOTO_INVALID", when = "Khoá ảnh không thuộc đơn này hoặc bị lặp.")
    @ApiResponse(responseCode = "201", description = "Đã tạo khiếu nại.")
    @PostMapping("/incident")
    @ResponseStatus(HttpStatus.CREATED)
    CaseViews.Case incident(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody CaseRequests.Incident request) {
        return cases.reportIncident(principal.id(), id, request);
    }

    @Operation(operationId = "quoteOrderIncident", summary = "Xem trước số tiền được hoàn", description = "Tính số tiền hoàn cho các dòng đã chọn trước khi gửi báo cáo; không tạo gì. Cùng điều kiện về đơn như khi báo.")
    @ApiError(status = 404, code = "ORDER_NOT_FOUND", when = "Đơn không tồn tại hoặc không phải của người gọi.")
    @ApiError(status = 409, code = "ORDER_NOT_DELIVERED", when = "Đơn chưa ở trạng thái đã giao.")
    @ApiError(status = 409, code = "REPORT_WINDOW_CLOSED", when = "Đã quá 24 giờ kể từ khi đơn được giao.")
    @ApiError(status = 409, code = "CASE_ALREADY_FILED", when = "Đơn đã có khiếu nại; kèm `caseId` để xem.")
    @ApiError(status = 400, code = "LINE_NOT_IN_ORDER", when = "Có món không thuộc đơn này.")
    @ApiError(status = 400, code = "LINE_REPEATED", when = "Một món được chọn nhiều lần.")
    @ApiError(status = 400, code = "QUANTITY_INVALID", when = "Số phần ngoài khoảng từ 1 đến số đã đặt.")
    @PostMapping("/incident/quote")
    CaseViews.Quote quote(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody CaseRequests.Quote request) {
        return cases.quote(principal.id(), id, request.lines());
    }

    @Operation(operationId = "uploadOrderCasePhoto", summary = "Tải ảnh cho báo cáo", description = "Tải một ảnh (JPEG, PNG hoặc WEBP, tối đa 5 MB, kiểm tra theo nội dung tệp) trước khi gửi báo cáo; ảnh được lưu riêng tư và chỉ xem được qua liên kết ngắn hạn. Gửi `photoKey` trong `photoKeys`. Giới hạn 10 ảnh mỗi giờ cho mỗi đơn.")
    @ApiError(status = 404, code = "ORDER_NOT_FOUND", when = "Đơn không tồn tại hoặc không phải của người gọi.")
    @ApiError(status = 409, code = "ORDER_NOT_DELIVERED", when = "Đơn chưa ở trạng thái đã giao.")
    @ApiError(status = 409, code = "REPORT_WINDOW_CLOSED", when = "Đã quá 24 giờ kể từ khi đơn được giao.")
    @ApiError(status = 409, code = "CASE_ALREADY_FILED", when = "Đơn đã có khiếu nại.")
    @ApiError(status = 413, code = "FILE_TOO_LARGE", when = "Ảnh vượt 5 MB.")
    @ApiError(status = 415, code = "UNSUPPORTED_FILE_TYPE", when = "Định dạng không được nhận (kiểm tra theo nội dung tệp).")
    @ApiError(status = 429, code = "TOO_MANY_UPLOADS", when = "Tải quá 10 ảnh trong một giờ cho đơn này.")
    @PostMapping(path = "/case-photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    CaseViews.Upload photo(CurrentPrincipal principal, @PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return cases.uploadPhoto(principal.id(), id, file);
    }

    @Operation(operationId = "getOrderCase", summary = "Xem khiếu nại của đơn", description = "Khiếu nại khách đã gửi cho đơn này kèm trạng thái, các dòng món, ảnh, câu trả lời của quán và quyết định (nếu có).")
    @ApiError(status = 404, code = "ORDER_NOT_FOUND", when = "Đơn không tồn tại hoặc không phải của người gọi.")
    @ApiError(status = 404, code = "CASE_NOT_FOUND", when = "Đơn này chưa có khiếu nại.")
    @GetMapping("/case")
    CaseViews.Case get(CurrentPrincipal principal, @PathVariable UUID id) {
        return cases.caseOf(principal.id(), id);
    }
}
