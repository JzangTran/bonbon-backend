package com.bonbon.backend.account.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.account.dto.GeoViews;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.geo.AdministrativeUnits;
import com.bonbon.backend.common.geo.Geocoder;
import com.bonbon.backend.common.geo.PlaceDetail;
import com.bonbon.backend.common.geo.PlaceSuggestion;
import com.bonbon.backend.common.ratelimit.RateLimiter;
import com.bonbon.backend.common.settings.SystemSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Address suggestions, units and map points for the shop wizard and the customer address form. Goes through the backend so the
 * Goong key never ships in an app, and a per-user limit protects the quota (address-and-geocoding.md).
 */
@Service
public class AddressSuggestionService {

    static final int MIN_INPUT = 3;

    private final Geocoder geocoder;
    private final RateLimiter rateLimiter;
    private final SystemSettingsService settings;
    private final AdministrativeUnits units;

    AddressSuggestionService(Geocoder geocoder, RateLimiter rateLimiter, SystemSettingsService settings, AdministrativeUnits units) {
        this.geocoder = geocoder;
        this.rateLimiter = rateLimiter;
        this.settings = settings;
        this.units = units;
    }

    public List<PlaceSuggestion> suggest(UUID userId, String input, Double nearLat, Double nearLng) {
        String text = input == null ? "" : input.strip();
        if (text.length() < MIN_INPUT) {
            return List.of();
        }
        limit(userId);
        return geocoder.autocomplete(text, nearLat, nearLng);
    }

    /** The 34 provinces and cities, for the first tab of the address picker. */
    public List<GeoViews.Province> provinces() {
        return units.provinces().stream().map(p -> new GeoViews.Province(p.code(), p.name(), p.type())).toList();
    }

    public List<GeoViews.Ward> wards(int provinceCode) {
        return units.wards(provinceCode)
                .orElseThrow(() -> BusinessException.notFound("PROVINCE_NOT_FOUND", "Không tìm thấy tỉnh hoặc thành phố này."))
                .stream().map(w -> new GeoViews.Ward(w.code(), w.name(), w.type())).toList();
    }

    /** A picked suggestion as a point on the map (one Place Detail call). */
    public GeoViews.Place place(UUID userId, String placeId) {
        limit(userId);
        PlaceDetail detail = geocoder.placeDetail(placeId).orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST,
                "ADDRESS_NOT_FOUND", "Không xác định được địa chỉ này. Hãy chọn lại một gợi ý."));
        return view(detail);
    }

    /** The address under the pin the user dropped on the map. */
    public GeoViews.Place reverse(UUID userId, double lat, double lng) {
        limit(userId);
        PlaceDetail detail = geocoder.reverse(lat, lng).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                "ADDRESS_NOT_FOUND", "Không có địa chỉ nào ở vị trí này. Hãy dời bản đồ một chút."));
        return view(detail);
    }

    private void limit(UUID userId) {
        int limit = settings.getInt("geo.autocomplete_per_user_per_minute", 30);
        if (!rateLimiter.tryAcquire("geo_autocomplete:user:" + userId, limit, Duration.ofMinutes(1))) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                    "Bạn thao tác quá nhanh, vui lòng thử lại sau.");
        }
    }

    /** Matches the provider's names to the list of units, and cuts the street part out of the full address. */
    private GeoViews.Place view(PlaceDetail d) {
        var province = units.findProvince(d.province());
        var ward = province.flatMap(p -> units.findWard(p.code(), d.ward()));
        String street = streetOf(d.formattedAddress(), d.ward(), d.province());
        return new GeoViews.Place(d.placeId(), d.formattedAddress(), street, d.lat(), d.lng(), d.province(), d.ward(),
                province.map(AdministrativeUnits.Province::code).orElse(null), ward.map(AdministrativeUnits.Ward::code).orElse(null));
    }

    /**
     * The leading parts of a full address (house number, street, ward, province) before the first part that names an administrative unit (the ward, the
     * province, or anything that starts like one: quận, huyện, phường, xã, thành phố, tỉnh...); null when nothing is left.
     */
    public static String streetOf(String formatted, String ward, String province) {
        if (formatted == null || formatted.isBlank()) {
            return null;
        }
        List<String> street = new ArrayList<>();
        for (String part : Arrays.stream(formatted.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList()) {
            String plain = AdministrativeUnits.plain(part);
            String lower = java.text.Normalizer.normalize(part.toLowerCase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}+", "").replace('đ', 'd');
            boolean unit = plain.equals(AdministrativeUnits.plain(ward)) || plain.equals(AdministrativeUnits.plain(province))
                    || plain.equals("viet nam") || lower.matches("^(quan|huyen|thi xa|thi tran|thanh pho|tinh|phuong|xa|dac khu) .*");
            if (unit) {
                break;
            }
            street.add(part);
        }
        return street.isEmpty() ? null : String.join(", ", street);
    }
}
