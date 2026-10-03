package com.bonbon.backend.account.service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.geo.Geocoder;
import com.bonbon.backend.common.geo.PlaceSuggestion;
import com.bonbon.backend.common.ratelimit.RateLimiter;
import com.bonbon.backend.common.settings.SystemSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Address suggestions for the shop wizard and the customer address form. Goes through the backend so the
 * Goong key never ships in an app, and a per-user limit protects the quota (address-and-geocoding.md).
 */
@Service
public class AddressSuggestionService {

    static final int MIN_INPUT = 3;

    private final Geocoder geocoder;
    private final RateLimiter rateLimiter;
    private final SystemSettingsService settings;

    AddressSuggestionService(Geocoder geocoder, RateLimiter rateLimiter, SystemSettingsService settings) {
        this.geocoder = geocoder;
        this.rateLimiter = rateLimiter;
        this.settings = settings;
    }

    public List<PlaceSuggestion> suggest(UUID userId, String input, Double nearLat, Double nearLng) {
        String text = input == null ? "" : input.strip();
        if (text.length() < MIN_INPUT) {
            return List.of();
        }
        int limit = settings.getInt("geo.autocomplete_per_user_per_minute", 30);
        if (!rateLimiter.tryAcquire("geo_autocomplete:user:" + userId, limit, Duration.ofMinutes(1))) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                    "Bạn thao tác quá nhanh, vui lòng thử lại sau.");
        }
        return geocoder.autocomplete(text, nearLat, nearLng);
    }
}
