package com.bonbon.backend.inputs;

import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.identity.authentication.Role;
import com.bonbon.backend.identity.authentication.dto.RegisterRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.bonbon.backend.inputs.Inputs.expand;
import static com.bonbon.backend.inputs.Inputs.verdict;
import static org.assertj.core.api.Assertions.assertThat;

/** UC-001 Đăng ký tài khoản: the request record's rules (email, password length, name). The password policy is in CommonRulesTests. */
class RegisterInputTests {

    @ParameterizedTest(name = "{0} -> {5}")
    @CsvSource(delimiter = '|', textBlock = """
            DK-01 | an@example.com   | Bep-Sen-2027 | Nguyễn Văn An | CUSTOMER | OK
            DK-02 | ''               | Bep-Sen-2027 | Nguyễn Văn An | CUSTOMER | email
            DK-03 | an@gmail         | Bep-Sen-2027 | Nguyễn Văn An | CUSTOMER | email
            DK-04 | an..b@example.com| Bep-Sen-2027 | Nguyễn Văn An | CUSTOMER | email
            DK-05 | letters:250      | Bep-Sen-2027 | Nguyễn Văn An | CUSTOMER | email
            DK-06 | an@example.com   | Abc-123      | Nguyễn Văn An | CUSTOMER | password
            DK-07 | an@example.com   | Bep-Sen1     | Nguyễn Văn An | CUSTOMER | OK
            DK-08 | an@example.com   | letters:100  | Nguyễn Văn An | CUSTOMER | OK
            DK-09 | an@example.com   | letters:101  | Nguyễn Văn An | CUSTOMER | password
            DK-10 | an@example.com   | Bep-Sen-2027 | 123           | CUSTOMER | name
            DK-11 | an@example.com   | Bep-Sen-2027 | A             | CUSTOMER | name
            DK-12 | an@example.com   | Bep-Sen-2027 | Lê            | CUSTOMER | OK
            DK-13 | an@example.com   | Bep-Sen-2027 | letters:101   | CUSTOMER | name
            DK-14 | an@example.com   | Bep-Sen-2027 | Nguyễn Văn An | SELLER   | OK
            DK-15 | an@example.com   | Bep-Sen-2027 | Nguyễn Văn An |          | role
            DK-16 | ''               | 1234         | 9             |          | email+name+password+role
            """)
    void register(String id, String email, String password, String name, Role role, String expected) {
        var request = new RegisterRequest(expand(email), expand(password), expand(name), role, Set.of(UUID.randomUUID()), false, null, null);
        assertThat(verdict(request)).as(id).isEqualTo(expected);
    }
}
