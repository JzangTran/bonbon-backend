package com.bonbon.backend.identity.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** What the address forms read from the geo endpoints. */
public final class GeoViews {

    private GeoViews() {
    }

    @Schema(name = "GeoProvince", description = "Tỉnh hoặc thành phố trực thuộc trung ương (34 đơn vị sau sáp nhập 2025).")
    public record Province(
            @Schema(description = "Mã đơn vị hành chính; dùng để gửi kèm biểu mẫu.", example = "1") int code,
            @Schema(example = "Thành phố Hà Nội") String name,
            @Schema(description = "tỉnh hoặc thành phố trung ương.") String type) {
    }

    @Schema(name = "GeoWard", description = "Phường, xã hoặc đặc khu (không còn cấp quận/huyện).")
    public record Ward(
            @Schema(description = "Mã đơn vị hành chính; duy nhất trên cả nước.", example = "10101") int code,
            @Schema(example = "Phường Tây Mỗ") String name,
            @Schema(description = "phường, xã hoặc đặc khu.") String type) {
    }

    @Schema(name = "GeoPlace", description = "Một điểm trên bản đồ, đã đổi ra địa chỉ.")
    public record Place(
            @Schema(description = "Mã địa điểm của Goong; trống khi điểm lấy từ vị trí ghim.") String placeId,
            @Schema(description = "Địa chỉ đầy đủ theo Goong.") String formattedAddress,
            @Schema(description = "Phần số nhà và đường (đã bỏ phường/xã và tỉnh), để điền sẵn ô địa chỉ chi tiết; có thể trống.") String street,
            double lat,
            double lng,
            @Schema(description = "Tên tỉnh theo Goong.") String province,
            @Schema(description = "Tên phường/xã theo Goong.") String ward,
            @Schema(description = "Mã tỉnh khớp với danh sách; trống khi không khớp được.") Integer provinceCode,
            @Schema(description = "Mã phường/xã khớp với danh sách; trống khi không khớp được.") Integer wardCode) {
    }
}
