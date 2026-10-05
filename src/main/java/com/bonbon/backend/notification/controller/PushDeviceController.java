package com.bonbon.backend.notification.controller;

import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.notification.dto.PushDeviceRequests;
import com.bonbon.backend.notification.service.PushDeviceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** An app registers or retires its push token. Any logged-in user may, for their own devices only. */
@RestController
@RequestMapping("/api/push-devices")
class PushDeviceController {

    private final PushDeviceService devices;

    PushDeviceController(PushDeviceService devices) {
        this.devices = devices;
    }

    /** Upsert: call after login and whenever the token changes. */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void register(CurrentPrincipal principal, @Valid @RequestBody PushDeviceRequests.Register request) {
        devices.register(principal.id(), request.token(), request.platform(), request.appVersion());
    }

    /** Call on logout so this phone stops receiving this account's pushes. */
    @PostMapping("/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(CurrentPrincipal principal, @Valid @RequestBody PushDeviceRequests.Revoke request) {
        devices.revoke(principal.id(), request.token());
    }
}
