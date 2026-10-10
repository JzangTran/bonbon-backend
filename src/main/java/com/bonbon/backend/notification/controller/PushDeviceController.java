package com.bonbon.backend.notification.controller;

import java.util.UUID;

import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.notification.dto.PreferenceViews;
import com.bonbon.backend.notification.dto.PushDeviceRequests;
import com.bonbon.backend.notification.service.PushDeviceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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

/** An app registers or retires its push token. Any logged-in user may, for their own devices only. */
@Tag(name = ApiTags.PUSH_DEVICES, description = "Ứng dụng đăng ký mã nhận thông báo đẩy (Expo) cho thiết bị.")
@RestController
@RequestMapping("/api/push-devices")
class PushDeviceController {

    private final PushDeviceService devices;

    PushDeviceController(PushDeviceService devices) {
        this.devices = devices;
    }

    /** Upsert: call after login and whenever the token changes. */
    @Operation(operationId = "registerPushDevice", summary = "Đăng ký thiết bị", description = "Gọi sau khi người dùng cho phép thông báo và mỗi khi mã đổi. Một mã chỉ thuộc một tài khoản: đăng ký lại bởi tài khoản khác thì mã chuyển sang tài khoản đó.")
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void register(CurrentPrincipal principal, @Valid @RequestBody PushDeviceRequests.Register request) {
        devices.register(principal.id(), request.token(), request.platform(), request.appVersion());
    }

    /** Call on logout so this phone stops receiving this account's pushes. */
    @Operation(operationId = "revokePushDevice", summary = "Huỷ đăng ký thiết bị", description = "Gọi khi đăng xuất, trước khi xoá token, để máy này thôi nhận thông báo của tài khoản. Mã của tài khoản khác được giữ nguyên.")
    @PostMapping("/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(CurrentPrincipal principal, @Valid @RequestBody PushDeviceRequests.Revoke request) {
        devices.revoke(principal.id(), request.token());
    }

    @Operation(operationId = "listPushDevices", summary = "Thiết bị đang nhận thông báo đẩy", description = "Các thiết bị đang hoạt động của tài khoản, lần dùng gần nhất trước, để người dùng gỡ máy đã mất.")
    @GetMapping
    PreferenceViews.Devices list(CurrentPrincipal principal) {
        return new PreferenceViews.Devices(devices.activeOf(principal.id()).stream()
                .map(d -> new PreferenceViews.Device(d.getId(), d.getPlatform(), d.getAppVersion(), d.getLastSeenAt())).toList());
    }

    @Operation(operationId = "removePushDevice", summary = "Gỡ một thiết bị", description = "Thiết bị thôi nhận thông báo đẩy của tài khoản này (đăng ký lại sẽ nhận lại). Thông báo trong ứng dụng không đổi.")
    @ApiError(status = 404, code = "DEVICE_NOT_FOUND", when = "Không có thiết bị đang hoạt động này trong tài khoản của người gọi.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(CurrentPrincipal principal, @PathVariable UUID id) {
        devices.revokeById(principal.id(), id);
    }
}
