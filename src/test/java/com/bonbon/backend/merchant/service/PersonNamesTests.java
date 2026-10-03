package com.bonbon.backend.merchant.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonNamesTests {

    @Test
    void comparesLikeABankPrintsNames() {
        assertThat(PersonNames.sameName("Nguyễn Văn  An", "NGUYEN VAN AN")).isTrue();
        assertThat(PersonNames.sameName("Đặng Thị Đào", "dang thi dao")).isTrue();
        assertThat(PersonNames.sameName("Trần Văn B", "TRAN VAN C")).isFalse();
        assertThat(PersonNames.sameName(null, "A")).isNull();
        assertThat(PersonNames.sameName(" ", "A")).isNull();
    }
}
