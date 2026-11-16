package com.bonbon.backend.inputs;

import java.math.BigDecimal;
import java.util.Collections;

import com.bonbon.backend.marketplace.servicearea.dto.ServiceAreaRequests;
import com.bonbon.backend.payments.payment.dto.RefundRequests;
import com.bonbon.backend.support.dto.ContactRequests;
import com.bonbon.backend.support.dto.TicketRequests;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.bonbon.backend.inputs.Inputs.expand;
import static com.bonbon.backend.inputs.Inputs.verdict;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * UC-007 Thanh toán (the account a refund goes to), UC-025 Quản lý khu vực phục vụ and UC-030 Hỗ trợ khách (support ticket, contact
 * form).
 */
class SupportAndAdminInputTests {

    @ParameterizedTest(name = "{0} -> {4}")
    @CsvSource(delimiter = '|', textBlock = """
            HT-01 | Vietcombank   | 0071001234567 | NGUYEN VAN AN   | OK
            HT-02 | vcb           | 123456        | Nguyễn Văn An   | OK
            HT-03 | Vietcombak    | 0071001234567 | NGUYEN VAN AN   | bankName
            HT-04 | ''            | 0071001234567 | NGUYEN VAN AN   | bankName
            HT-05 | Vietcombank   | 12345         | NGUYEN VAN AN   | accountNumber
            HT-06 | Vietcombank   | digits:19     | NGUYEN VAN AN   | OK
            HT-07 | Vietcombank   | digits:20     | NGUYEN VAN AN   | accountNumber
            HT-08 | Vietcombank   | 0071001234567 | NGUYEN VAN AN 1 | accountName
            HT-09 | Vietcombank   | 0071001234567 | ''              | accountName
            """)
    void refundDestination(String id, String bank, String account, String name, String expected) {
        assertThat(verdict(new RefundRequests.Destination(bank, expand(account), name))).as(id).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {6}")
    @CsvSource(delimiter = '|', textBlock = """
            KV-01 | Vinhomes Smart City | 21.0035 | 105.7480 | 2.0  | 1.5  | OK
            KV-02 | A                   | 21.0035 | 105.7480 | 2.0  | 1.5  | name
            KV-03 | AB                  | 21.0035 | 105.7480 | 2.0  | 1.5  | OK
            KV-04 | letters:101         | 21.0035 | 105.7480 | 2.0  | 1.5  | name
            KV-05 | Khu A               | 5.9     | 105.7480 | 2.0  | 1.5  | centerLat
            KV-06 | Khu A               | 21.0035 | 118.1    | 2.0  | 1.5  | centerLng
            KV-07 | Khu A               | 21.0035 | 105.7480 | 0.05 | 1.5  | radiusKm
            KV-08 | Khu A               | 21.0035 | 105.7480 | 50.1 | 1.5  | radiusKm
            KV-09 | Khu A               | 21.0035 | 105.7480 | 2.25 | 1.5  | radiusKm
            KV-10 | Khu A               | 21.0035 | 105.7480 | 2.0  | 0    | maxDeliveryRadiusKm
            KV-11 | Khu A               | 21.0035 | 105.7480 | 50   | 50   | OK
            """)
    void serviceArea(String id, String name, Double lat, Double lng, BigDecimal radius, BigDecimal maxDelivery, String expected) {
        assertThat(verdict(new ServiceAreaRequests.Save(expand(name), lat, lng, radius, maxDelivery, true))).as(id).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {4}")
    @CsvSource(delimiter = '|', textBlock = """
            PH-01 | Chưa nhận được hoàn tiền | Đơn ngày 12/11 bị huỷ nhưng chưa thấy tiền | 0 | OK
            PH-02 | ''                       | Nội dung                                   | 0 | subject
            PH-03 | letters:150              | Nội dung                                   | 3 | OK
            PH-04 | letters:151              | Nội dung                                   | 0 | subject
            PH-05 | Tiêu đề                  | letters:2001                               | 0 | message
            PH-06 | Tiêu đề                  | Nội dung                                   | 4 | attachmentKeys
            """)
    void ticket(String id, String subject, String message, int attachments, String expected) {
        var request = new TicketRequests.Open(expand(subject), expand(message), null, Collections.nCopies(attachments, "support/a.jpg"));
        assertThat(verdict(request)).as(id).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {6}")
    @CsvSource(delimiter = '|', textBlock = """
            LH-01 | Trần Thị Sen | sen@example.com | SELLER   | PAYMENT | Hỏi về đối soát | OK
            LH-02 | Sen 99       | sen@example.com | SELLER   | PAYMENT | Hỏi             | fullName
            LH-03 | Trần Thị Sen | sen@example     | SELLER   | PAYMENT | Hỏi             | email
            LH-04 | Trần Thị Sen | sen@example.com | ADMIN    | PAYMENT | Hỏi             | role
            LH-05 | Trần Thị Sen | sen@example.com | CUSTOMER | REFUND  | Hỏi             | topic
            LH-06 | Trần Thị Sen | sen@example.com | OTHER    | OTHER   | letters:2001    | message
            """)
    void contact(String id, String name, String email, String role, String topic, String message, String expected) {
        assertThat(verdict(new ContactRequests.Submit(name, email, role, topic, expand(message), null))).as(id).isEqualTo(expected);
    }
}
