package com.bonbon.backend.inputs;

import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

import com.bonbon.backend.marketplace.merchant.dto.MenuRequests;
import com.bonbon.backend.marketplace.merchant.dto.OptionRequests;
import com.bonbon.backend.marketplace.merchant.dto.VoucherViews;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.bonbon.backend.inputs.Inputs.expand;
import static com.bonbon.backend.inputs.Inputs.verdict;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * UC-017 Quản lý thực đơn and UC-018 Quản lý voucher: a dish, an option group, a voucher. Cross-field rules that need the stored data
 * (min not above max, a percentage at most 100, the validity window) are checked by the services and covered by MenuTests and
 * VoucherTests.
 */
class MenuAndVoucherInputTests {

    @ParameterizedTest(name = "{0} -> {6}")
    @CsvSource(delimiter = '|', textBlock = """
            TD-01 | Cơm gà xối mỡ | Giòn, kèm canh | 45000    |        | 20     | OK
            TD-02 | ''            |                | 45000    |        |        | name
            TD-03 | letters:100   |                | 45000    |        |        | OK
            TD-04 | letters:101   |                | 45000    |        |        | name
            TD-05 | Cơm gà        | letters:501    | 45000    |        |        | description
            TD-06 | Cơm gà        |                | -1       |        |        | price
            TD-07 | Cơm gà        |                | 0        |        |        | OK
            TD-08 | Cơm gà        |                | 10000000 |        |        | OK
            TD-09 | Cơm gà        |                | 10000001 |        |        | price
            TD-10 | Cơm gà        |                |          |        |        | price
            TD-11 | Cơm gà        |                | 45000    | 55000  | 100000 | OK
            TD-12 | Cơm gà        |                | 45000    | -5     | 100001 | originalPrice+stockQuantity
            """)
    void dish(String id, String name, String description, Integer price, Integer original, Integer stock, String expected) {
        var request = new MenuRequests.ItemCreate(UUID.randomUUID(), UUID.randomUUID(), expand(name), expand(description), price, stock, original);
        assertThat(verdict(request)).as(id).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {6}")
    @CsvSource(delimiter = '|', textBlock = """
            TC-01 | Cỡ ly      | 1  | 1  | 3  | 5000    | OK
            TC-02 | ''         | 1  | 1  | 3  | 5000    | name
            TC-03 | letters:61 | 1  | 1  | 3  | 5000    | name
            TC-04 | Topping    | -1 | 2  | 3  | 5000    | min
            TC-05 | Topping    | 0  | 31 | 3  | 5000    | max
            TC-06 | Topping    | 0  | 3  | 0  | 5000    | options
            TC-07 | Topping    | 0  | 3  | 31 | 5000    | options
            TC-08 | Topping    | 0  | 3  | 3  | -1      | options.priceDelta
            TC-09 | Topping    | 0  | 3  | 3  | 1000001 | options.priceDelta
            """)
    void optionGroup(String id, String name, int min, int max, int options, int priceDelta, String expected) {
        var option = new OptionRequests.Option(null, "Lựa chọn", priceDelta, false, null);
        var request = new OptionRequests.Group(expand(name), min, max, Collections.nCopies(options, option));
        assertThat(verdict(request)).as(id).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {8}")
    @CsvSource(delimiter = '|', textBlock = """
            VC-01 | SALE20       | PERCENT | 20    | 30000 | 100000 | 100   | 1    | OK
            VC-02 | GIAM_10K     | FIXED   | 10000 |       |        |       |      | OK
            VC-03 | AB           | FIXED   | 10000 |       |        |       |      | code
            VC-04 | letters:20   | FIXED   | 10000 |       |        |       |      | OK
            VC-05 | letters:21   | FIXED   | 10000 |       |        |       |      | code
            VC-06 | SALE 20      | FIXED   | 10000 |       |        |       |      | code
            VC-07 | GIẢM20       | FIXED   | 10000 |       |        |       |      | code
            VC-08 | SALE20       | FREE    | 10000 |       |        |       |      | discountType
            VC-09 | SALE20       | FIXED   | 0     |       |        |       |      | discountValue
            VC-10 | SALE20       | PERCENT | 20    | 0     | -1     | 0     | 0    | maxDiscount+maxPerCustomer+minOrderValue+usageLimit
            VC-11 | SALE20       | PERCENT | 20    |       |        |       | 1001 | maxPerCustomer
            """)
    void voucher(String id, String code, String type, int value, Integer maxDiscount, Integer minOrder, Integer limit, Integer perCustomer,
            String expected) {
        Instant from = Instant.parse("2026-12-01T00:00:00Z");
        var request = new VoucherViews.Create(expand(code), type, value, maxDiscount, minOrder, from, from.plusSeconds(7 * 86400), limit, perCustomer);
        assertThat(verdict(request)).as(id).isEqualTo(expected);
    }
}
