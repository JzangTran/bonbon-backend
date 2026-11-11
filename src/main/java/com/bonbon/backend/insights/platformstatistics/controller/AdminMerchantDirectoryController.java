package com.bonbon.backend.insights.platformstatistics.controller;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.insights.platformstatistics.dto.MerchantViews;
import com.bonbon.backend.insights.platformstatistics.service.MerchantDirectoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.ADMIN_MERCHANTS, description = "Quản trị viên tìm cửa hàng, xem chi tiết và xem vùng phủ thực tế. Chỉ đọc. Hồ sơ chờ duyệt vẫn ở mục Duyệt cửa hàng; đình chỉ ở mục riêng.")
@RestController
@RequestMapping("/api/admin/merchants")
@PreAuthorize("hasAuthority('merchant-approval:read')")
class AdminMerchantDirectoryController {

    private final MerchantDirectoryService directory;

    AdminMerchantDirectoryController(MerchantDirectoryService directory) {
        this.directory = directory;
    }

    @Operation(operationId = "listMerchants", summary = "Danh sách cửa hàng", description = "Mặc định là cửa hàng đang hoạt động (`APPROVED`), mới nhất trước. `status` là PENDING, APPROVED, REJECTED, SUSPENDED, CLOSED hoặc ALL; `q` tìm theo tên, phường, tỉnh, không phân biệt hoa thường và dấu. Mỗi dòng có điểm đánh giá, số đơn đã giao, điểm phạt còn hiệu lực, hạn chế hiển thị, giai đoạn nợ hoa hồng và lịch đình chỉ.")
    @ApiError(status = 400, code = "STATUS_FILTER_INVALID", when = "`status` không thuộc các giá trị cho phép.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–100.")
    @GetMapping
    MerchantViews.Page list(@RequestParam(required = false) String status, @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return directory.list(status, q, page, size);
    }

    @Operation(operationId = "getAreaCoverage", summary = "Vùng phủ", description = "Các cửa hàng đang hoạt động với vị trí, bán kính giao, số đơn đã giao; lọc theo `province` và `ward`. Quán có bán kính lớn hơn `outlierFactor` lần trung vị của tỉnh (cài đặt `coverage.outlier_factor`, mặc định 2) được đánh dấu để xem kỹ, không bị hạn chế. Không có vùng do quản trị vẽ: đây chỉ là quan sát.")
    @GetMapping("/coverage")
    MerchantViews.Coverage coverage(@RequestParam(required = false) String province, @RequestParam(required = false) String ward) {
        return directory.coverage(province, ward);
    }
}
