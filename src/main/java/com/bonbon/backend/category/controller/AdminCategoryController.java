package com.bonbon.backend.category.controller;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.category.dto.CategoryNode;
import com.bonbon.backend.category.dto.CategoryRequests;
import com.bonbon.backend.category.service.CategoryService;
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

/** Category management for administrators; the admin view includes hidden nodes. */
@RestController
@RequestMapping("/api/admin/categories")
@PreAuthorize("hasAuthority('category:write')")
class AdminCategoryController {

    private final CategoryService categories;

    AdminCategoryController(CategoryService categories) {
        this.categories = categories;
    }

    @GetMapping
    List<CategoryNode> tree() {
        return categories.tree(true);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CategoryNode create(CurrentPrincipal principal, @Valid @RequestBody CategoryRequests.Create request) {
        return categories.create(principal, request);
    }

    @PatchMapping("/{id}")
    CategoryNode update(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody CategoryRequests.Update request) {
        return categories.update(principal, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        categories.delete(id);
    }
}
