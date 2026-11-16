package com.bonbon.backend.inputs;

import java.util.Collections;
import java.util.UUID;

import com.bonbon.backend.ordering.order.dto.ReviewRequests;
import com.bonbon.backend.ordering.shopperformance.dto.CaseRequests;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.bonbon.backend.inputs.Inputs.expand;
import static com.bonbon.backend.inputs.Inputs.verdict;
import static org.assertj.core.api.Assertions.assertThat;

/** UC-010 Báo sự cố đơn hàng and UC-012 Đánh giá đơn: what the customer types after the order is delivered. */
class OrderAfterDeliveryInputTests {

    @ParameterizedTest(name = "{0} -> {6}")
    @CsvSource(delimiter = '|', textBlock = """
            SC-01 | MISSING_ITEM | 1  | 1 | 0  |             | OK
            SC-02 | WRONG_ITEM   | 2  | 3 | 3  | Sai món gà  | OK
            SC-03 | QUALITY      | 1  | 1 | 10 |             | OK
            SC-04 | LATE         | 1  | 1 | 0  |             | type
            SC-05 |              | 1  | 1 | 0  |             | type
            SC-06 | QUALITY      | 0  | 1 | 0  |             | lines
            SC-07 | QUALITY      | 51 | 1 | 0  |             | lines
            SC-08 | QUALITY      | 1  | 0 | 0  |             | lines.quantity
            SC-09 | QUALITY      | 1  | 1 | 11 |             | photoKeys
            SC-10 | QUALITY      | 1  | 1 | 0  | letters:500 | OK
            SC-11 | QUALITY      | 1  | 1 | 0  | letters:501 | note
            """)
    void incident(String id, String type, int lines, int quantity, int photos, String note, String expected) {
        var request = new CaseRequests.Incident(type, Collections.nCopies(lines, new CaseRequests.Line(UUID.randomUUID(), quantity)),
                Collections.nCopies(photos, "case/photo.jpg"), expand(note));
        assertThat(verdict(request)).as(id).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {3}")
    @CsvSource(delimiter = '|', textBlock = """
            DG-01 | 5 | Ngon, giao nhanh | OK
            DG-02 | 1 |                  | OK
            DG-03 | 0 |                  | rating
            DG-04 | 6 |                  | rating
            DG-05 | 4 | letters:1000      | OK
            DG-06 | 4 | letters:1001      | comment
            DG-07 | 0 | letters:1001      | comment+rating
            """)
    void review(String id, int rating, String comment, String expected) {
        assertThat(verdict(new ReviewRequests.Write(rating, expand(comment)))).as(id).isEqualTo(expected);
    }
}
