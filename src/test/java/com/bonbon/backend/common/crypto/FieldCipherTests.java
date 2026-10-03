package com.bonbon.backend.common.crypto;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldCipherTests {

    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static final String OTHER_KEY = Base64.getEncoder().encodeToString("another-key-of-exactly-32-bytes!".getBytes());

    @Test
    void roundTripsWithAFreshNonceEachTime() {
        FieldCipher cipher = new FieldCipher(KEY);
        String a = cipher.encrypt("001200012345");
        String b = cipher.encrypt("001200012345");
        assertThat(a).startsWith("v1:").isNotEqualTo(b).doesNotContain("001200012345");
        assertThat(cipher.decrypt(a)).isEqualTo("001200012345");
        assertThat(cipher.decrypt(b)).isEqualTo("001200012345");
    }

    @Test
    void tamperedValueOrWrongKeyIsRejected() {
        String sealed = new FieldCipher(KEY).encrypt("0011001234567");
        byte[] raw = Base64.getDecoder().decode(sealed.substring(3));
        raw[raw.length - 1] ^= 1;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> new FieldCipher(KEY).decrypt(tampered)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FieldCipher(OTHER_KEY).decrypt(sealed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyMustBe32BytesOfBase64() {
        assertThatThrownBy(() -> new FieldCipher("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FieldCipher("c2hvcnQ=")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FieldCipher("not base64!")).isInstanceOf(IllegalStateException.class);
    }
}
