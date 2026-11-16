package com.bonbon.backend.inputs;

import com.bonbon.backend.identity.account.dto.AddressDtos;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.bonbon.backend.inputs.Inputs.expand;
import static com.bonbon.backend.inputs.Inputs.verdict;
import static org.assertj.core.api.Assertions.assertThat;

/** UC-005 Quản lý địa chỉ giao hàng: adding an address (label, place, detail, recipient, phone, note). */
class AddressInputTests {

    @ParameterizedTest(name = "{0} -> {7}")
    @CsvSource(delimiter = '|', textBlock = """
            DC-01 | Nhà         | place-1 | Toà S2, căn 1205 | Trần Thị Sen | 0912345678    | Gọi trước khi lên | OK
            DC-02 | Công ty     | place-1 |                  | Trần Thị Sen | +84912345678  |                   | OK
            DC-03 | ''          | place-1 |                  | Trần Thị Sen | 0912345678    |                   | label
            DC-04 | letters:30  | place-1 |                  | Trần Thị Sen | 0912345678    |                   | OK
            DC-05 | letters:31  | place-1 |                  | Trần Thị Sen | 0912345678    |                   | label
            DC-06 | Nhà         | ''      |                  | Trần Thị Sen | 0912345678    |                   | placeId
            DC-07 | Nhà         | place-1 | letters:200      | Trần Thị Sen | 0912345678    |                   | OK
            DC-08 | Nhà         | place-1 | letters:201      | Trần Thị Sen | 0912345678    |                   | detail
            DC-09 | Nhà         | place-1 |                  | ''           | 0912345678    |                   | recipientName
            DC-10 | Nhà         | place-1 |                  | Sen 2        | 0912345678    |                   | recipientName
            DC-11 | Nhà         | place-1 |                  | Trần Thị Sen | ''            |                   | recipientPhone
            DC-12 | Nhà         | place-1 |                  | Trần Thị Sen | 0123456789    |                   | recipientPhone
            DC-13 | Nhà         | place-1 |                  | Trần Thị Sen | 02838123456   |                   | recipientPhone
            DC-14 | Nhà         | place-1 |                  | Trần Thị Sen | 0912 345 678  |                   | recipientPhone
            DC-15 | Nhà         | place-1 |                  | Trần Thị Sen | 0912345678    | letters:300       | OK
            DC-16 | Nhà         | place-1 |                  | Trần Thị Sen | 0912345678    | letters:301       | note
            """)
    void create(String id, String label, String placeId, String detail, String name, String phone, String note, String expected) {
        var request = new AddressDtos.Create(expand(label), expand(placeId), expand(detail), expand(name), expand(phone), null, expand(note));
        assertThat(verdict(request)).as(id).isEqualTo(expected);
    }
}
