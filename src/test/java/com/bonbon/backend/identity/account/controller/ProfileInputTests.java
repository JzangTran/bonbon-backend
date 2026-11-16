package com.bonbon.backend.identity.account.controller;

import com.bonbon.backend.inputs.Inputs;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** UC-014 Quản lý hồ sơ: name and phone of the signed-in person (an empty phone removes it). */
class ProfileInputTests {

    @ParameterizedTest(name = "{0} -> {3}")
    @CsvSource(delimiter = '|', textBlock = """
            HS-01 | Trần Thị Sen | 0912345678   | OK
            HS-02 | Trần Thị Sen | ''           | OK
            HS-03 | Trần Thị Sen |              | OK
            HS-04 | ''           | 0912345678   | name
            HS-05 | Sen 2        | 0912345678   | name
            HS-06 | letters:101  | 0912345678   | name
            HS-07 | Trần Thị Sen | 02838123456  | phone
            HS-08 | Trần Thị Sen | 0123456789   | phone
            HS-09 | Trần Thị Sen | +84912345678 | OK
            """)
    void update(String id, String name, String phone, String expected) {
        var request = new AccountController.UpdateProfileRequest(Inputs.expand(name), phone);
        assertThat(Inputs.verdict(request)).as(id).isEqualTo(expected);
    }
}
