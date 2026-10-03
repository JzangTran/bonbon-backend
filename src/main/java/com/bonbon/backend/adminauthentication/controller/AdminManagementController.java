package com.bonbon.backend.adminauthentication.controller;

import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.authentication.AdminAccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/manage/admins")
class AdminManagementController {

    record CreateAdminRequest(@NotBlank @Email @Size(max = 255) String email, @NotBlank @Size(max = 100) String name) {
    }

    private final AdminAccountService admins;

    AdminManagementController(AdminAccountService admins) {
        this.admins = admins;
    }

    /** The new administrator receives an email link to set their own password. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('admin:write')")
    Map<String, UUID> create(@Valid @RequestBody CreateAdminRequest request) {
        return Map.of("id", admins.createAdmin(request.email(), request.name()));
    }
}
