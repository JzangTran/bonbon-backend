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
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Suggestions while typing an address (≥ 3 characters; the client waits 300 ms between keystrokes). The
 * picked {@code placeId} is sent with the form and resolved once on save.
 */
@Tag(name = ApiTags.ADDRESS_SUGGESTIONS, description = "Gợi ý địa chỉ Việt Nam khi gõ (Goong), dùng cho địa chỉ giao hàng và địa chỉ quán.")
@RestController
@RequestMapping("/api/geo")
class AddressSuggestionController {

    private final AddressSuggestionService suggestions;

    AddressSuggestionController(AddressSuggestionService suggestions) {
        this.suggestions = suggestions;
    }

    @Operation(operationId = "suggestAddresses", summary = "Gợi ý địa chỉ", description = "Gọi khi người dùng đã gõ từ 3 ký tự và dừng 300 ms. Trả về `placeId` để gửi kèm biểu mẫu; toạ độ chỉ được tra khi lưu. Có thể truyền toạ độ hiện tại để ưu tiên kết quả gần.")
    @ApiError(status = 429, code = "TOO_MANY_REQUESTS", when = "Gửi quá nhiều lần trong thời gian ngắn; đợi rồi thử lại.")
    @ApiError(status = 503, code = "GEOCODING_UNAVAILABLE", when = "Dịch vụ tra địa chỉ (Goong) tạm thời không phản hồi; thử lại sau.")
    @GetMapping("/autocomplete")
    List<PlaceSuggestion> autocomplete(CurrentPrincipal principal, @RequestParam @Size(max = 200) String input,
            @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng) {
        return suggestions.suggest(principal.id(), input, lat, lng);
    }
}
