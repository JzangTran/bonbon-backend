package com.bonbon.backend.legal.controller;

import com.bonbon.backend.legal.DocumentType;
import com.bonbon.backend.legal.LegalConsentService;
import com.bonbon.backend.legal.LegalDocumentView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/legal/documents")
class LegalDocumentController {

    private final LegalConsentService legal;

    LegalDocumentController(LegalConsentService legal) {
        this.legal = legal;
    }

    /** Public: the version in force, shown and linked from registration forms. */
    @GetMapping("/{type}")
    LegalDocumentView current(@PathVariable DocumentType type) {
        return legal.current(type);
    }
}
