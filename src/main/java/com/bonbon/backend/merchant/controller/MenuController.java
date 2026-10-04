package com.bonbon.backend.merchant.controller;

import java.util.UUID;

import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.merchant.dto.MenuRequests;
import com.bonbon.backend.merchant.dto.MenuView;
import com.bonbon.backend.merchant.service.MenuService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The caller's own menu. Every write answers with the whole menu so a client can simply replace its copy. */
@RestController
@RequestMapping("/api/merchant")
class MenuController {

    private final MenuService menu;

    MenuController(MenuService menu) {
        this.menu = menu;
    }

    @GetMapping("/menu")
    @PreAuthorize("hasAuthority('vendor:read')")
    MenuView get(CurrentPrincipal principal) {
        return menu.menu(principal);
    }

    @PostMapping("/menu-sections")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('vendor:write')")
    MenuView createSection(CurrentPrincipal principal, @Valid @RequestBody MenuRequests.Section request) {
        return menu.createSection(principal, request);
    }

    /** Bulk reorder: the full list of section ids in the new order. */
    @PutMapping("/menu-sections/order")
    @PreAuthorize("hasAuthority('vendor:write')")
    MenuView reorderSections(CurrentPrincipal principal, @Valid @RequestBody MenuRequests.Order request) {
        return menu.reorderSections(principal, request);
    }

    @PatchMapping("/menu-sections/{id}")
    @PreAuthorize("hasAuthority('vendor:write')")
    MenuView renameSection(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody MenuRequests.Section request) {
        return menu.renameSection(principal, id, request);
    }

    @DeleteMapping("/menu-sections/{id}")
    @PreAuthorize("hasAuthority('vendor:write')")
    MenuView deleteSection(CurrentPrincipal principal, @PathVariable UUID id) {
        return menu.deleteSection(principal, id);
    }

    @PutMapping("/menu-sections/{id}/items/order")
    @PreAuthorize("hasAuthority('vendor:write')")
    MenuView reorderItems(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody MenuRequests.Order request) {
        return menu.reorderItems(principal, id, request);
    }

    @PostMapping("/menu-items")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('vendor:write')")
    MenuView createItem(CurrentPrincipal principal, @Valid @RequestBody MenuRequests.ItemCreate request) {
        return menu.createItem(principal, request);
    }

    @PatchMapping("/menu-items/{id}")
    @PreAuthorize("hasAuthority('vendor:write')")
    MenuView updateItem(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody MenuRequests.ItemUpdate request) {
        return menu.updateItem(principal, id, request);
    }

    /** Archives the dish; orders that reference it keep reading it. */
    @DeleteMapping("/menu-items/{id}")
    @PreAuthorize("hasAuthority('vendor:write')")
    MenuView deleteItem(CurrentPrincipal principal, @PathVariable UUID id) {
        return menu.deleteItem(principal, id);
    }

    @PutMapping(path = "/menu-items/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('vendor:write')")
    MenuView setPhoto(CurrentPrincipal principal, @PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return menu.setPhoto(principal, id, file);
    }

    @DeleteMapping("/menu-items/{id}/photo")
    @PreAuthorize("hasAuthority('vendor:write')")
    MenuView removePhoto(CurrentPrincipal principal, @PathVariable UUID id) {
        return menu.removePhoto(principal, id);
    }
}
