package com.bonbon.backend.inputs;

import com.bonbon.backend.common.validation.KnownBank;
import com.bonbon.backend.common.validation.PasswordPolicy;
import com.bonbon.backend.common.validation.PersonName;
import com.bonbon.backend.common.validation.RealEmail;
import com.bonbon.backend.common.validation.VnIdNumber;
import com.bonbon.backend.common.validation.VnPhone;
import com.bonbon.backend.common.validation.VnPhoneValidator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared rules every form relies on (common/validation), checked on their own with equivalence classes and boundary values. Each
 * row is one case of the report's tables (KT-PH, KT-EM, KT-HT, KT-NH, KT-GT, KT-MK).
 */
class CommonRulesTests {

    @ParameterizedTest(name = "{0}: {1} as {2} -> {3}")
    @CsvSource(delimiter = '|', textBlock = """
            KT-PH-01 | 0912345678     | MOBILE | true
            KT-PH-02 | +84912345678   | MOBILE | true
            KT-PH-03 | 0387654321     | MOBILE | true
            KT-PH-04 | 0701234567     | MOBILE | true
            KT-PH-05 | 0123456789     | MOBILE | false
            KT-PH-06 | 0000000000     | MOBILE | false
            KT-PH-07 | 091234567      | MOBILE | false
            KT-PH-08 | 09123456789    | MOBILE | false
            KT-PH-09 | 0912 345 678   | MOBILE | false
            KT-PH-10 | 84912345678    | MOBILE | false
            KT-PH-11 | 02838123456    | MOBILE | false
            KT-PH-12 | 02838123456    | ANY    | true
            KT-PH-13 | 02438123456    | ANY    | true
            KT-PH-14 | 0912345678     | ANY    | true
            KT-PH-15 | 0900000000abc  | ANY    | false
            """)
    void phone(String id, String number, VnPhone.Kind kind, boolean valid) {
        assertThat(VnPhoneValidator.isValid(number, kind)).as(id).isEqualTo(valid);
    }

    @ParameterizedTest(name = "{0}: {1} -> {2}")
    @CsvSource(delimiter = '|', textBlock = """
            KT-EM-01 | an@example.com           | true
            KT-EM-02 | an.nguyen+food@gmail.com | true
            KT-EM-03 | AN@EXAMPLE.COM           | true
            KT-EM-04 | an@gmail                 | false
            KT-EM-05 | an@localhost             | false
            KT-EM-06 | an..b@example.com        | false
            KT-EM-07 | .an@example.com          | false
            KT-EM-08 | an.@example.com          | false
            KT-EM-09 | an @example.com          | false
            KT-EM-10 | an@example.c             | false
            KT-EM-11 | an@-example.com          | false
            KT-EM-12 | an@example.com.vn        | true
            KT-EM-13 | anexample.com            | false
            """)
    void email(String id, String email, boolean valid) {
        assertThat(RealEmail.Validator.isValid(email)).as(id).isEqualTo(valid);
    }

    @ParameterizedTest(name = "{0}: {1} -> {2}")
    @CsvSource(delimiter = '|', textBlock = """
            KT-HT-01 | Nguyễn Văn An     | true
            KT-HT-02 | Lê                | true
            KT-HT-03 | A                 | false
            KT-HT-04 | Nguyễn Văn An 2   | false
            KT-HT-05 | 123               | false
            KT-HT-06 | Trần-Thị Ánh      | true
            KT-HT-07 | Nguyễn T. Mai     | true
            KT-HT-08 | NGUYEN VAN A      | true
            KT-HT-09 | An@Nguyen         | false
            KT-HT-10 | '  Bình  '        | true
            KT-HT-11 | O'Connor          | true
            KT-HT-12 | An   Nguyễn       | false
            """)
    void personName(String id, String name, boolean valid) {
        assertThat(PersonName.Validator.isValid(name)).as(id).isEqualTo(valid);
    }

    @ParameterizedTest(name = "{0}: {1} -> {2}")
    @CsvSource(delimiter = '|', textBlock = """
            KT-NH-01 | Vietcombank   | true
            KT-NH-02 | VCB           | true
            KT-NH-03 | vietcom bank  | true
            KT-NH-04 | Ngân hàng TMCP Ngoại Thương Việt Nam | true
            KT-NH-05 | Vietcombak    | false
            KT-NH-06 | Ngân hàng ABC | false
            KT-NH-07 | ''            | true
            """)
    void bank(String id, String bank, boolean valid) {
        assertThat(new KnownBank.Validator().isValid(bank, null)).as(id).isEqualTo(valid);
    }

    @ParameterizedTest(name = "{0}: {1} {2} -> {3}")
    @CsvSource(delimiter = '|', textBlock = """
            KT-GT-01 | CCCD | 001200012345 | OK
            KT-GT-02 | CCCD | 079099012345 | OK
            KT-GT-03 | CCCD | 003200012345 | mã tỉnh
            KT-GT-04 | CCCD | 001500012345 | thứ tư
            KT-GT-05 | CCCD | 001299012345 | tương lai
            KT-GT-06 | CCCD | 00120001234  | 12 chữ số
            KT-GT-07 | CMND | 123456789    | OK
            KT-GT-08 | CMND | 001200012345 | OK
            KT-GT-09 | CMND | 12345678     | 9 hoặc 12
            KT-GT-10 | CMND | 999200012345 | mã tỉnh
            """)
    void identityNumber(String id, String type, String number, String expected) {
        var problem = "CCCD".equals(type) ? VnIdNumber.citizenIdProblem(number) : VnIdNumber.identityCardProblem(number);
        if ("OK".equals(expected)) {
            assertThat(problem).as(id).isEmpty();
        } else {
            assertThat(problem).as(id).hasValueSatisfying(m -> assertThat(m).contains(expected));
        }
    }

    @ParameterizedTest(name = "{0}: {1} -> {4}")
    @CsvSource(delimiter = '|', textBlock = """
            KT-MK-01 | Bep-Sen-2027      | an@example.com        | Trần Thị Sen | OK
            KT-MK-02 | 1234567           | an@example.com        | Trần Thị Sen | ít nhất 8
            KT-MK-03 | Bep-Sen1          | an@example.com        | Trần Thị Sen | OK
            KT-MK-04 | letters:101       | an@example.com        | Trần Thị Sen | tối đa 100
            KT-MK-05 | 12345678          | an@example.com        | Trần Thị Sen | phổ biến
            KT-MK-06 | MatKhau123        | an@example.com        | Trần Thị Sen | phổ biến
            KT-MK-07 | aaaaaaaa          | an@example.com        | Trần Thị Sen | lặp lại
            KT-MK-08 | abababab          | an@example.com        | Trần Thị Sen | lặp lại
            KT-MK-09 | abcdefghij        | an@example.com        | Trần Thị Sen | liên tiếp
            KT-MK-10 | 98765432          | an@example.com        | Trần Thị Sen | liên tiếp
            KT-MK-11 | xinchao.annguyen  | annguyen@example.com  | Trần Thị Sen | email
            KT-MK-12 | minhanh2027!      | an@example.com        | Minh Anh     | họ tên
            KT-MK-13 | hoa-sen-tren-ho   | an@example.com        | Trần Thị Sen | OK
            """)
    void password(String id, String password, String email, String name, String expected) {
        var problem = PasswordPolicy.problem(Inputs.expand(password), email, name);
        if ("OK".equals(expected)) {
            assertThat(problem).as(id).isEmpty();
        } else {
            assertThat(problem).as(id).hasValueSatisfying(m -> assertThat(m).contains(expected));
        }
    }
}
