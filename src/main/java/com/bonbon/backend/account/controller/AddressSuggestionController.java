package com.bonbon.backend.account.controller;

import java.util.List;

import com.bonbon.backend.account.dto.GeoViews;
import com.bonbon.backend.account.service.AddressSuggestionService;
import com.bonbon.backend.common.geo.PlaceSuggestion;
import com.bonbon.backend.common.security.CurrentPrincipal;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @Operation(operationId = "listProvinces", summary = "Danh sách tỉnh, thành phố", description = "34 tỉnh và thành phố trực thuộc trung ương sau sáp nhập (2025). Danh sách nằm trong ứng dụng, không gọi bên ngoài; dùng cho ô chọn Tỉnh/Thành phố.")
    @GetMapping("/provinces")
    List<GeoViews.Province> provinces() {
        return suggestions.provinces();
    }

    @Operation(operationId = "listWards", summary = "Danh sách phường, xã của một tỉnh", description = "Phường, xã, đặc khu của tỉnh hoặc thành phố đã chọn (không còn cấp quận/huyện). Dùng cho ô chọn Phường/Xã.")
    @ApiError(status = 404, code = "PROVINCE_NOT_FOUND", when = "Mã tỉnh không có trong danh sách.")
    @GetMapping("/provinces/{code}/wards")
    List<GeoViews.Ward> wards(@PathVariable int code) {
        return suggestions.wards(code);
    }

    @Operation(operationId = "getPlace", summary = "Toạ độ của một gợi ý địa chỉ", description = "Đổi `placeId` của một gợi ý thành điểm trên bản đồ (một lần gọi Place Detail của Goong), kèm mã tỉnh và phường/xã khớp với danh sách. Dùng khi người dùng chọn một dòng gợi ý trong cửa sổ bản đồ.")
    @ApiError(status = 400, code = "ADDRESS_NOT_FOUND", when = "Không xác định được địa chỉ này.")
    @ApiError(status = 429, code = "TOO_MANY_REQUESTS", when = "Gửi quá nhiều lần trong thời gian ngắn; đợi rồi thử lại.")
    @ApiError(status = 503, code = "GEOCODING_UNAVAILABLE", when = "Dịch vụ tra địa chỉ (Goong) tạm thời không phản hồi; thử lại sau.")
    @GetMapping("/place")
    GeoViews.Place place(CurrentPrincipal principal, @RequestParam @NotBlank @Size(max = 1024) String placeId) {
        return suggestions.place(principal.id(), placeId);
    }

    @Operation(operationId = "reverseGeocode", summary = "Địa chỉ tại một điểm trên bản đồ", description = "Gọi khi người dùng dừng kéo bản đồ: đổi toạ độ ở tâm bản đồ thành địa chỉ, kèm mã tỉnh và phường/xã khớp với danh sách. Toạ độ phải nằm trong lãnh thổ Việt Nam.")
    @ApiError(status = 404, code = "ADDRESS_NOT_FOUND", when = "Không có địa chỉ nào ở vị trí này.")
    @ApiError(status = 429, code = "TOO_MANY_REQUESTS", when = "Gửi quá nhiều lần trong thời gian ngắn; đợi rồi thử lại.")
    @ApiError(status = 503, code = "GEOCODING_UNAVAILABLE", when = "Dịch vụ tra địa chỉ (Goong) tạm thời không phản hồi; thử lại sau.")
    @GetMapping("/reverse")
    GeoViews.Place reverse(CurrentPrincipal principal,
            @RequestParam @DecimalMin("6.0") @DecimalMax("24.0") double lat,
            @RequestParam @DecimalMin("102.0") @DecimalMax("118.0") double lng) {
        return suggestions.reverse(principal.id(), lat, lng);
    }
}
