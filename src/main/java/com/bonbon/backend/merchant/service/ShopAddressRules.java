package com.bonbon.backend.merchant.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.geo.AdministrativeUnits;
import com.bonbon.backend.merchant.entity.Vendor;
import com.bonbon.backend.servicearea.ServiceAreas;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * A shop's address as the owner enters it: a province and a ward from the list the application ships, the detail they type
 * (house number, street) and the point they confirmed on the map. The full address shown to customers is built here from those
 * parts, so it always reads the same way and the ward always belongs to the province.
 */
@Component
class ShopAddressRules {

    private final AdministrativeUnits units;
    private final ServiceAreas areas;

    ShopAddressRules(AdministrativeUnits units, ServiceAreas areas) {
        this.units = units;
        this.areas = areas;
    }

    /**
     * Writes the address onto the vendor. Nothing sent at all clears it (a draft step may be saved empty). Returns true when
     * the place (units or map point) now differs from what was stored, so a caller can decide whether it needs a review; a
     * change of the detail alone does not count.
     */
    boolean apply(Vendor vendor, Integer provinceCode, Integer wardCode, String addressDetail, Double lat, Double lng) {
        String detail = addressDetail == null || addressDetail.isBlank() ? null : addressDetail.strip();
        String before = signature(vendor);
        if (provinceCode == null && wardCode == null && lat == null && lng == null) {
            vendor.setAddress(null, null, null, null, null, null);
            vendor.setAddressUnits(null, null);
            vendor.setServiceAreaId(null);
            vendor.setAddressDetail(detail);
            return !before.equals(signature(vendor));
        }

        List<Map<String, String>> errors = new ArrayList<>();
        AdministrativeUnits.Province province = null;
        AdministrativeUnits.Ward ward = null;
        if (provinceCode != null) {
            province = units.province(provinceCode).orElse(null);
            if (province == null) {
                errors.add(Map.of("field", "provinceCode", "message", "Tỉnh/Thành phố không hợp lệ."));
            }
        }
        if (wardCode != null) {
            if (provinceCode == null) {
                errors.add(Map.of("field", "wardCode", "message", "Hãy chọn Tỉnh/Thành phố trước."));
            } else if (province != null) {
                ward = units.ward(provinceCode, wardCode).orElse(null);
                if (ward == null) {
                    errors.add(Map.of("field", "wardCode", "message", "Phường/Xã không thuộc Tỉnh/Thành phố đã chọn."));
                }
            }
        }
        if ((lat == null) != (lng == null)) {
            errors.add(Map.of("field", "location", "message", "Vị trí trên bản đồ chưa đầy đủ."));
        }
        // The area is looked up when the point is new, or the shop has none yet. A shop approved before areas existed that did not
        // move keeps having none; one that moves must land inside an area.
        boolean pointMoved = lat != null && lng != null && !(lat.equals(vendor.getLat()) && lng.equals(vendor.getLng()));
        ServiceAreas.Area area = null;
        if (errors.isEmpty() && lat != null && lng != null && (pointMoved || vendor.getServiceAreaId() == null)) {
            area = areas.locate(lat, lng).orElse(null);
            if (area == null && pointMoved) {
                errors.add(Map.of("field", "location", "message", "Vị trí này nằm ngoài các khu vực bonbon đang phục vụ. Hãy chọn một vị trí khác."));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Một số thông tin chưa hợp lệ.")
                    .withProperty("errors", errors);
        }
        if (lat == null) {
            vendor.setServiceAreaId(null);
        } else if (area != null) {
            vendor.setServiceAreaId(area.id());
        }

        String formatted = String.join(", ", Stream.of(detail, ward == null ? null : ward.name(), province == null ? null : province.name())
                .filter(s -> s != null && !s.isBlank()).toList());
        vendor.setAddress(null, formatted.isEmpty() ? null : formatted, ward == null ? null : ward.name(),
                province == null ? null : province.name(), lat, lng);
        vendor.setAddressUnits(provinceCode, wardCode);
        vendor.setAddressDetail(detail);
        return !before.equals(signature(vendor));
    }

    private static String signature(Vendor v) {
        return v.getProvinceCode() + "|" + v.getWardCode() + "|" + v.getLat() + "|" + v.getLng();
    }
}
