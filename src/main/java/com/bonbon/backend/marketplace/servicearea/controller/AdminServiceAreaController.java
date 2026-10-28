package com.bonbon.backend.marketplace.servicearea.controller;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.marketplace.servicearea.dto.ServiceAreaRequests;
import com.bonbon.backend.marketplace.servicearea.dto.ServiceAreaView;
import com.bonbon.backend.marketplace.servicearea.service.ServiceAreaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.ADMIN_SERVICE_AREAS, description = "Các khu vực bonbon phục vụ do quản trị viên lập: mỗi khu vực là hình tròn quanh một tâm, kèm bán kính giao tối đa cho quán trong khu vực.")
@RestController
@RequestMapping("/api/admin/service-areas")
class AdminServiceAreaController {

    private final ServiceAreaService areas;

    AdminServiceAreaController(ServiceAreaService areas) {
        this.areas = areas;
    }

    @Operation(operationId = "listServiceAreas", summary = "Danh sách khu vực phục vụ", description = "Gồm cả khu vực đã tắt, xếp theo tên.")
    @GetMapping
    @PreAuthorize("hasAuthority('merchant-approval:read')")
    List<ServiceAreaView> list() {
        return areas.list();
    }

    @Operation(operationId = "createServiceArea", summary = "Thêm khu vực phục vụ", description = "Quán có vị trí nằm trong hình tròn của khu vực (khu vực có tâm gần nhất nếu chồng nhau) thuộc về khu vực đó.")
    @ApiError(status = 409, code = "SERVICE_AREA_NAME_TAKEN", when = "Đã có khu vực trùng tên.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('merchant-approval:write-settings')")
    ServiceAreaView create(CurrentPrincipal principal, @Valid @RequestBody ServiceAreaRequests.Save request) {
        return areas.create(principal, request);
    }

    @Operation(operationId = "updateServiceArea", summary = "Sửa khu vực phục vụ", description = "Đổi tên, tâm, bán kính, bán kính giao tối đa hoặc tắt/bật (`active`). Khu vực đã tắt không nhận thêm quán mới; quán đang thuộc khu vực đó giữ nguyên. Giới hạn mới áp dụng cho các lần lưu bán kính sau.")
    @ApiError(status = 404, code = "SERVICE_AREA_NOT_FOUND", when = "Không có khu vực với id này.")
    @ApiError(status = 409, code = "SERVICE_AREA_NAME_TAKEN", when = "Đã có khu vực trùng tên.")
    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('merchant-approval:write-settings')")
    ServiceAreaView update(@PathVariable UUID id, @Valid @RequestBody ServiceAreaRequests.Save request) {
        return areas.update(id, request);
    }
}
