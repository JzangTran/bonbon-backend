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

/**
 * Delivery addresses exist to place orders, so they follow the ordering permission (customers only). Always
 * the caller's own addresses.
 */
@RestController
@RequestMapping("/api/account/addresses")
@PreAuthorize("hasAuthority('order:create')")
class AddressController {

    private final AddressService addresses;

    AddressController(AddressService addresses) {
        this.addresses = addresses;
    }

    /** Default first, then newest. */
    @GetMapping
    List<AddressDtos.View> list(CurrentPrincipal principal) {
        return addresses.list(principal.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AddressDtos.View create(CurrentPrincipal principal, @Valid @RequestBody AddressDtos.Create request) {
        return addresses.create(principal.id(), request);
    }

    @PatchMapping("/{id}")
    AddressDtos.View update(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody AddressDtos.Update request) {
        return addresses.update(principal.id(), id, request);
    }

    @PatchMapping("/{id}/default")
    AddressDtos.View makeDefault(CurrentPrincipal principal, @PathVariable UUID id) {
        return addresses.makeDefault(principal.id(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(CurrentPrincipal principal, @PathVariable UUID id) {
        addresses.delete(principal.id(), id);
    }
}
