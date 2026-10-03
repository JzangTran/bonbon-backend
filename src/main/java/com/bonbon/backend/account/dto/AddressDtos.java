package com.bonbon.backend.account.dto;

import java.util.UUID;

import com.bonbon.backend.account.entity.Address;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AddressDtos {

    /** Vietnamese mobile number: 0 or +84 followed by 9 digits starting 3, 5, 7, 8 or 9. */
    public static final String VN_MOBILE = "^(0|\\+84)(3|5|7|8|9)\\d{8}$";

    private AddressDtos() {
    }

    /** {@code placeId} is a suggestion from /api/geo/autocomplete; a free-text-only address cannot be saved. */
    public record Create(
            @NotBlank @Size(max = 30) String label,
            @NotBlank @Size(max = 1024) String placeId,
            @Size(max = 200) String detail,
            @NotBlank @Size(max = 100) String recipientName,
            @NotBlank @Pattern(regexp = VN_MOBILE, message = "Số điện thoại di động Việt Nam không hợp lệ") String recipientPhone,
            Boolean makeDefault) {
    }

    /** Only the fields present change; a new {@code placeId} is resolved again, editing {@code detail} is free. */
    public record Update(
            @Size(min = 1, max = 30) String label,
            @Size(min = 1, max = 1024) String placeId,
            @Size(max = 200) String detail,
            @Size(min = 1, max = 100) String recipientName,
            @Pattern(regexp = VN_MOBILE, message = "Số điện thoại di động Việt Nam không hợp lệ") String recipientPhone) {
    }

    public record View(UUID id, String label, String placeId, String formattedAddress, String ward, String province,
            String detail, String recipientName, String recipientPhone, double lat, double lng, boolean isDefault) {

        public static View of(Address a) {
            return new View(a.getId(), a.getLabel(), a.getPlaceId(), a.getFormattedAddress(), a.getWard(), a.getProvince(),
                    a.getDetail(), a.getRecipientName(), a.getRecipientPhone(), a.getLat(), a.getLng(), a.isDefaultAddress());
        }
    }
}
