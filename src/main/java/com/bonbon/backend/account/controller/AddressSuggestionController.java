package com.bonbon.backend.account.controller;

import java.util.List;

import com.bonbon.backend.account.service.AddressSuggestionService;
import com.bonbon.backend.common.geo.PlaceSuggestion;
import com.bonbon.backend.common.security.CurrentPrincipal;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Suggestions while typing an address (≥ 3 characters; the client waits 300 ms between keystrokes). The
 * picked {@code placeId} is sent with the form and resolved once on save.
 */
@RestController
@RequestMapping("/api/geo")
class AddressSuggestionController {

    private final AddressSuggestionService suggestions;

    AddressSuggestionController(AddressSuggestionService suggestions) {
        this.suggestions = suggestions;
    }

    @GetMapping("/autocomplete")
    List<PlaceSuggestion> autocomplete(CurrentPrincipal principal, @RequestParam @Size(max = 200) String input,
            @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng) {
        return suggestions.suggest(principal.id(), input, lat, lng);
    }
}
