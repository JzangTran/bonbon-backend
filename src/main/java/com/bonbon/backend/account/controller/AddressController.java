package com.bonbon.backend.account.controller;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.account.dto.AddressDtos;
import com.bonbon.backend.account.service.AddressService;
import com.bonbon.backend.common.security.CurrentPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Delivery addresses exist to place orders, so they follow the ordering permission (customers only). Always
 * the caller's own addresses.
 */
@Tag(name = ApiTags.CUSTOMER_ADDRESSES, description = "Địa chỉ nhận món của khách: tối đa 10, luôn có đúng một địa chỉ mặc định. Chỉ của chính khách đang đăng nhập.")
@RestController
@RequestMapping("/api/account/addresses")
@PreAuthorize("hasAuthority('order:create')")
class AddressController {

    private final AddressService addresses;

    AddressController(AddressService addresses) {
        this.addresses = addresses;
    }

    /** Default first, then newest. */
    @Operation(operationId = "listMyAddresses", summary = "Danh sách địa chỉ", description = "Địa chỉ mặc định đứng đầu, sau đó mới nhất trước.")
    @GetMapping
    List<AddressDtos.View> list(CurrentPrincipal principal) {
        return addresses.list(principal.id());
    }

    @Operation(operationId = "createAddress", summary = "Thêm địa chỉ", description = "`placeId` lấy từ API gợi ý địa chỉ; máy chủ tra toạ độ một lần khi lưu. `makeDefault` đặt làm mặc định; địa chỉ đầu tiên luôn là mặc định.")
    @ApiError(status = 409, code = "ADDRESS_LIMIT_REACHED", when = "Đã có đủ 10 địa chỉ.")
    @ApiError(status = 400, code = "ADDRESS_NOT_FOUND", when = "`placeId` không tra được ra địa chỉ.")
    @ApiError(status = 503, code = "GEOCODING_UNAVAILABLE", when = "Dịch vụ tra địa chỉ (Goong) tạm thời không phản hồi; thử lại sau.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AddressDtos.View create(CurrentPrincipal principal, @Valid @RequestBody AddressDtos.Create request) {
        return addresses.create(principal.id(), request);
    }

    @Operation(operationId = "updateAddress", summary = "Sửa địa chỉ", description = "Chỉ trường gửi lên mới đổi. Đổi `placeId` thì tra lại toạ độ; chỉ đổi dòng chi tiết hay người nhận thì không. Đơn đã đặt giữ bản sao địa chỉ cũ.")
    @ApiError(status = 404, code = "ADDRESS_NOT_FOUND", when = "Địa chỉ không tồn tại hoặc của người khác.")
    @ApiError(status = 503, code = "GEOCODING_UNAVAILABLE", when = "Dịch vụ tra địa chỉ (Goong) tạm thời không phản hồi; thử lại sau.")
    @PatchMapping("/{id}")
    AddressDtos.View update(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody AddressDtos.Update request) {
        return addresses.update(principal.id(), id, request);
    }

    @Operation(operationId = "setDefaultAddress", summary = "Đặt làm mặc định", description = "Địa chỉ mặc định cũ tự bỏ mặc định trong cùng một thao tác.")
    @ApiError(status = 404, code = "ADDRESS_NOT_FOUND", when = "Địa chỉ không tồn tại hoặc của người khác.")
    @PatchMapping("/{id}/default")
    AddressDtos.View makeDefault(CurrentPrincipal principal, @PathVariable UUID id) {
        return addresses.makeDefault(principal.id(), id);
    }

    @Operation(operationId = "deleteAddress", summary = "Xoá địa chỉ", description = "Xoá mặc định thì địa chỉ mới nhất còn lại thành mặc định. Đơn đã đặt không bị ảnh hưởng.")
    @ApiError(status = 404, code = "ADDRESS_NOT_FOUND", when = "Địa chỉ không tồn tại hoặc của người khác.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(CurrentPrincipal principal, @PathVariable UUID id) {
        addresses.delete(principal.id(), id);
    }
}
