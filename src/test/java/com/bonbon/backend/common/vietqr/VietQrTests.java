package com.bonbon.backend.common.vietqr;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The VietQR payload: EMVCo fields in order, the NAPAS account block, and the CRC that closes it. */
class VietQrTests {

    @Test
    void theChecksumIsCrc16CcittFalse() {
        // the standard check value of CRC-16/CCITT-FALSE
        assertThat(VietQr.crc("123456789")).isEqualTo("29B1");
    }

    @Test
    void aTransferCodeCarriesTheBankTheAccountTheAmountAndTheMessage() {
        String payload = VietQr.payload("970436", "0011001234567", 85000, "BONBON125");

        assertThat(payload).startsWith("000201010212");
        // 38: NAPAS guid, then the bank BIN and the account, then "to an account"
        assertThat(payload).contains("38570010A000000727012700069704360113001100123456702" + "08QRIBFTTA");
        assertThat(payload).contains("5303704" + "540585000" + "5802VN" + "62130809BONBON125");
        String body = payload.substring(0, payload.length() - 4);
        assertThat(body).endsWith("6304");
        assertThat(payload.substring(payload.length() - 4)).isEqualTo(VietQr.crc(body));
    }

    @Test
    void aBankIsFoundByWhatAShopTypes() {
        assertThat(VietQr.bank("Vietcombank")).hasValueSatisfying(b -> assertThat(b.bin()).isEqualTo("970436"));
        assertThat(VietQr.bank("vcb")).hasValueSatisfying(b -> assertThat(b.bin()).isEqualTo("970436"));
        assertThat(VietQr.bank("MB Bank")).hasValueSatisfying(b -> assertThat(b.bin()).isEqualTo("970422"));
        assertThat(VietQr.bank("Ngân hàng không có")).isEmpty();
        assertThat(VietQr.bank(null)).isEmpty();
    }
}
