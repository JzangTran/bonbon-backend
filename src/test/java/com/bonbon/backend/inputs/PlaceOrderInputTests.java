package com.bonbon.backend.inputs;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.ordering.order.dto.OrderRequests;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.bonbon.backend.inputs.Inputs.expand;
import static com.bonbon.backend.inputs.Inputs.verdict;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * UC-006 Đặt món: the request record (payment method, number of lines, quantity, notes, voucher code). What depends on the database
 * (shop open, dish available, inside the radius, voucher valid, minimum order) is covered by OrderPlacementTests.
 */
class PlaceOrderInputTests {

    @ParameterizedTest(name = "{0} -> {7}")
    @CsvSource(delimiter = '|', textBlock = """
            DM-01 | COD    | 1  | 1   |             |             |            | OK
            DM-02 | ONLINE | 2  | 99  | ít cay      | Gọi trước   | SALE20     | OK
            DM-03 | CARD   | 1  | 1   |             |             |            | paymentMethod
            DM-04 |        | 1  | 1   |             |             |            | paymentMethod
            DM-05 | COD    | 0  | 1   |             |             |            | items
            DM-06 | COD    | 50 | 1   |             |             |            | OK
            DM-07 | COD    | 51 | 1   |             |             |            | items
            DM-08 | COD    | 1  | 0   |             |             |            | items.quantity
            DM-09 | COD    | 1  | 100 |             |             |            | items.quantity
            DM-10 | COD    | 1  | 1   | letters:200 |             |            | OK
            DM-11 | COD    | 1  | 1   | letters:201 |             |            | items.note
            DM-12 | COD    | 1  | 1   |             | letters:300 |            | OK
            DM-13 | COD    | 1  | 1   |             | letters:301 |            | note
            DM-14 | COD    | 1  | 1   |             |             | letters:20 | OK
            DM-15 | COD    | 1  | 1   |             |             | letters:21 | voucherCode
            """)
    void place(String id, String method, int lines, int quantity, String lineNote, String note, String voucher, String expected) {
        List<OrderRequests.Line> items = Collections.nCopies(lines, new OrderRequests.Line(UUID.randomUUID(), quantity, List.of(), expand(lineNote)));
        var request = new OrderRequests.Place(UUID.randomUUID(), UUID.randomUUID(), method, expand(note), items, expand(voucher));
        assertThat(verdict(request)).as(id).isEqualTo(expected);
    }
}
