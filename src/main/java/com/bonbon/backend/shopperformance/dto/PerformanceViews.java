package com.bonbon.backend.shopperformance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** How a shop is doing on the orders it fails (flows/shop-performance/view-shop-performance.md). */
public final class PerformanceViews {

    private PerformanceViews() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "PerformanceStanding")
    public record Standing(
            @Schema(description = "Điểm phạt còn hiệu lực (cấp trong 90 ngày gần nhất, chưa được miễn).") int activePoints,
            @Schema(description = "NONE, WARNING (1–2 điểm: chỉ cảnh báo), RESTRICTION_SCHEDULED (từ 3 điểm: đã báo, chưa áp dụng) hoặc RESTRICTED (đang hạn chế hiển thị).") String consequence,
            @Schema(description = "Khi hạn chế hiển thị bắt đầu; luôn cách thông báo ít nhất 5 ngày.") Instant restrictionStartsAt,
            @Schema(description = "Lý do hạn chế: PERFORMANCE (điểm phạt). Nợ hoa hồng quá hạn là một lý do khác, xem mục Thu nhập.") String reason) {
    }

    @Schema(name = "PerformanceWeek")
    public record Week(
            @Schema(description = "Thứ Hai đầu tuần, giờ Việt Nam.") LocalDate start,
            @Schema(description = "Chủ nhật cuối tuần.") LocalDate end,
            @Schema(description = "Số đơn đã đến bước quán nhận và kết thúc trong tuần.") long finishedOrders,
            @Schema(description = "Số đơn thất bại do quán.") long faultOrders,
            @Schema(description = "Tỷ lệ phần trăm; luôn có, nhưng chỉ tính điểm phạt khi `counted` là true.") BigDecimal ratePercent,
            @Schema(description = "Đủ số đơn tối thiểu (10) để tuần này được đánh giá.") boolean counted,
            @Schema(description = "Tuần này đã bị cộng điểm phạt.") boolean penalised,
            @Schema(description = "Tuần đang diễn ra: số liệu tạm tính, chưa đánh giá.") boolean current) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "ShopPenalty")
    public record Penalty(
            UUID id,
            int points,
            @Schema(description = "WEEKLY (đánh giá hằng tuần) hoặc MANUAL (quản trị viên cộng).") String source,
            LocalDate weekStart,
            String reason,
            @Schema(description = "ACTIVE hoặc WAIVED (đã được miễn).") String status,
            Instant issuedAt,
            @Schema(description = "Hết hiệu lực sau 90 ngày kể từ khi cấp.") Instant expiresAt,
            @Schema(description = "Còn kháng nghị được: điểm đang hiệu lực, chưa kháng nghị, trong thời hạn 7 ngày.") boolean canAppeal,
            @Schema(description = "Hạn kháng nghị.") Instant appealDeadline,
            @Schema(description = "PENDING, ACCEPTED hoặc REJECTED; vắng mặt khi chưa kháng nghị.") String appealStatus,
            @Schema(description = "Lý do quản trị viên quyết định kháng nghị hoặc miễn điểm.") String decisionReason) {
    }

    @Schema(name = "ShopFault")
    public record Fault(
            UUID orderId,
            long orderNumber,
            @Schema(description = "SHOP_REJECTED (quán từ chối), NO_RESPONSE (không trả lời kịp), HANDOVER_TIMEOUT (không giao đi kịp), SHOP_CANCELLED (huỷ sau khi nhận), INCIDENT_FULL_REFUND (khiếu nại hoàn cả đơn) hoặc NO_SHOW_SHOP_AT_FAULT (quán không đến).") String type,
            Instant at) {
    }

    @Schema(name = "ShopPerformance")
    public record Summary(
            Standing standing,
            @Schema(description = "Tuần hiện tại (tạm tính) rồi 8 tuần đã đóng gần nhất, mới nhất trước.") List<Week> weeks,
            List<Penalty> penalties,
            @Schema(description = "Ngưỡng tỷ lệ lỗi (phần trăm); vượt ngưỡng một tuần đủ đơn thì bị cộng 1 điểm.") int thresholdPercent,
            @Schema(description = "Số đơn tối thiểu để một tuần được đánh giá.") int minOrders) {
    }
}
