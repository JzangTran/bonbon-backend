package com.bonbon.backend.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts sensitive columns at rest (identity document numbers, bank account numbers): AES-256-GCM with a
 * random 96-bit nonce per value, stored as {@code v1:<base64(nonce || ciphertext+tag)>}. The version prefix
 * leaves room for key rotation. The key comes from {@code BONBON_DATA_KEY} (32 bytes, base64) and is never
 * committed; losing it makes these columns unreadable, so it is kept with the other secrets.
 */
@Component
public class FieldCipher {

    private static final String VERSION = "v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public FieldCipher(@Value("${bonbon.security.data-key:}") String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException e) {
            raw = new byte[0];
        }
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "bonbon.security.data-key (env BONBON_DATA_KEY) must be 32 bytes, base64; generate: openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + sealed.length)
                    .put(nonce).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (!stored.startsWith(VERSION)) {
            throw new IllegalArgumentException("Unknown ciphertext version");
        }
        byte[] all = Base64.getDecoder().decode(stored.substring(VERSION.length()));
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, NONCE_BYTES));
            return new String(cipher.doFinal(all, NONCE_BYTES, all.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed (wrong key or tampered value)", e);
        }
    }
}
