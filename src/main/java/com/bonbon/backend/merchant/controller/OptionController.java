package com.bonbon.backend.merchant.controller;

import java.util.UUID;

import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.merchant.dto.OptionGroupsView;
import com.bonbon.backend.merchant.dto.OptionRequests;
import com.bonbon.backend.merchant.service.OptionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own option groups. Every write answers with all groups so a client can replace its copy. */
@RestController
@RequestMapping("/api/merchant")
class OptionController {

    private final OptionService optionService;

    OptionController(OptionService optionService) {
        this.optionService = optionService;
    }

    @GetMapping("/option-groups")
    @PreAuthorize("hasAuthority('vendor:read')")
    OptionGroupsView list(CurrentPrincipal principal) {
        return optionService.list(principal);
    }

    @PostMapping("/option-groups")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('vendor:write')")
    OptionGroupsView create(CurrentPrincipal principal, @Valid @RequestBody OptionRequests.Group request) {
        return optionService.create(principal, request);
    }

    /** Replaces the group and its options; options left out are archived. */
    @PutMapping("/option-groups/{id}")
    @PreAuthorize("hasAuthority('vendor:write')")
    OptionGroupsView update(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody OptionRequests.Group request) {
        return optionService.update(principal, id, request);
    }

    /** Detaches the group from its dishes and archives it. */
    @DeleteMapping("/option-groups/{id}")
    @PreAuthorize("hasAuthority('vendor:write')")
    OptionGroupsView delete(CurrentPrincipal principal, @PathVariable UUID id) {
        return optionService.delete(principal, id);
    }

    @PatchMapping("/options/{id}/status")
    @PreAuthorize("hasAuthority('vendor:write')")
    OptionGroupsView setStatus(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody OptionRequests.Status request) {
        return optionService.setOptionStatus(principal, id, request.status());
    }
}
