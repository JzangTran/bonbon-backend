package com.bonbon.backend.inputs;

import java.math.BigDecimal;
import java.util.List;

import com.bonbon.backend.marketplace.merchant.dto.ShopApplicationRequests;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.bonbon.backend.inputs.Inputs.expand;
import static com.bonbon.backend.inputs.Inputs.verdict;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * UC-015 Mở quán: the four steps of the shop application. A draft may leave fields empty, so these check that what is present is well
 * formed; ShopApplicationTests covers submitting with missing parts and the service rules (radius within the area, ID number structure).
 */
class OpenShopInputTests {

    @ParameterizedTest(name = "{0} -> {6}")
    @CsvSource(delimiter = '|', textBlock = """
            MQ1-01 | Cơm nhà Bếp Sen | 0912345678   | quan@example.com | 21.01 | 105.75 | OK
            MQ1-02 | Cơm nhà Bếp Sen | 02838123456  | quan@example.com | 10.77 | 106.70 | OK
            MQ1-03 | letters:100     | 0912345678   | quan@example.com | 21.01 | 105.75 | OK
            MQ1-04 | letters:101     | 0912345678   | quan@example.com | 21.01 | 105.75 | name
            MQ1-05 | Cơm nhà Bếp Sen | 0000000000   | quan@example.com | 21.01 | 105.75 | phone
            MQ1-06 | Cơm nhà Bếp Sen | 091234567    | quan@example.com | 21.01 | 105.75 | phone
            MQ1-07 | Cơm nhà Bếp Sen | 0912345678   | quan@example     | 21.01 | 105.75 | email
            MQ1-08 | Cơm nhà Bếp Sen | 0912345678   | quan@example.com | 5.99  | 105.75 | lat
            MQ1-09 | Cơm nhà Bếp Sen | 0912345678   | quan@example.com | 24.01 | 105.75 | lat
            MQ1-10 | Cơm nhà Bếp Sen | 0912345678   | quan@example.com | 21.01 | 118.01 | lng
            MQ1-11 |                 |              |                  |       |        | OK
            """)
    void shopInfo(String id, String name, String phone, String email, Double lat, Double lng, String expected) {
        var request = new ShopApplicationRequests.Step1(expand(name), phone, email, null, null, null, lat, lng);
        assertThat(verdict(request)).as(id).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {5}")
    @CsvSource(delimiter = '|', textBlock = """
            MQ2-01 | 1.5   | 15000   | 100000    | 30000     | OK
            MQ2-02 | 0.1   | 0       | 0         | 0         | OK
            MQ2-03 | 0.05  | 0       |           |           | deliveryRadiusKm
            MQ2-04 | 1.25  | 0       |           |           | deliveryRadiusKm
            MQ2-05 | 99.9  | 1000000 |           |           | OK
            MQ2-06 | 100   | 0       |           |           | deliveryRadiusKm
            MQ2-07 | 1.5   | -1      |           |           | deliveryFee
            MQ2-08 | 1.5   | 1000001 |           |           | deliveryFee
            MQ2-09 | 1.5   | 0       | 100000001 | -1        | freeDeliveryThreshold+minOrderValue
            """)
    void delivery(String id, BigDecimal radius, Integer fee, Integer freeFrom, Integer minOrder, String expected) {
        var request = new ShopApplicationRequests.Step2(List.of(), radius, fee, freeFrom, minOrder);
        assertThat(verdict(request)).as(id).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {6}")
    @CsvSource(delimiter = '|', textBlock = """
            MQ3-01 | 0101234567      | hd@example.com | Vietcombank | 0071001234567 | TRAN THI SEN     | OK
            MQ3-02 | 0101234567-001  | hd@example.com | VCB         | 123456        | Trần Thị Sen     | OK
            MQ3-03 | 001200012345    | hd@example.com | BIDV        | digits:19     | TRAN THI SEN     | OK
            MQ3-04 | 010123456       | hd@example.com | Vietcombank | 0071001234567 | TRAN THI SEN     | taxCode
            MQ3-05 | 0101234567-01   | hd@example.com | Vietcombank | 0071001234567 | TRAN THI SEN     | taxCode
            MQ3-06 | 0101234567      | hd@example     | Vietcombank | 0071001234567 | TRAN THI SEN     | invoiceEmails
            MQ3-07 | 0101234567      | hd@example.com | Vietcombak  | 0071001234567 | TRAN THI SEN     | bankName
            MQ3-08 | 0101234567      | hd@example.com | Vietcombank | 12345         | TRAN THI SEN     | accountNumber
            MQ3-09 | 0101234567      | hd@example.com | Vietcombank | digits:20     | TRAN THI SEN     | accountNumber
            MQ3-10 | 0101234567      | hd@example.com | Vietcombank | 0071-001-234  | TRAN THI SEN     | accountNumber
            MQ3-11 | 0101234567      | hd@example.com | Vietcombank | 0071001234567 | TRAN THI SEN 2   | accountHolderName
            """)
    void taxAndPayout(String id, String taxCode, String invoiceEmail, String bank, String account, String holder, String expected) {
        var request = new ShopApplicationRequests.Step3(null, null, null, taxCode, List.of(invoiceEmail), bank, expand(account), holder);
        assertThat(verdict(request)).as(id).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {4}")
    @CsvSource(delimiter = '|', textBlock = """
            MQ4-01 | CCCD | 001200012345  | Trần Thị Sen   | OK
            MQ4-02 | CMND | 123456789     | Trần Thị Sen   | OK
            MQ4-03 | CCCD | 00120001234   | Trần Thị Sen   | docNumber
            MQ4-04 | CCCD | 0012000123456 | Trần Thị Sen   | docNumber
            MQ4-05 | CCCD | 00120001234a  | Trần Thị Sen   | docNumber
            MQ4-06 | CCCD | 001200012345  | Trần Thị Sen 2 | fullName
            MQ4-07 | CCCD | 001200012345  | letters:101    | fullName
            """)
    void identity(String id, String type, String number, String fullName, String expected) {
        var request = new ShopApplicationRequests.Step4(
                com.bonbon.backend.marketplace.merchant.entity.VendorIdentity.DocType.valueOf(type), number, expand(fullName), true, null, null);
        assertThat(verdict(request)).as(id).isEqualTo(expected);
    }
}
