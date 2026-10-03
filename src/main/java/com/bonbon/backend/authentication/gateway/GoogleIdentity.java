package com.bonbon.backend.authentication.gateway;

/** The claims bonbon uses from a verified Google ID token. */
public record GoogleIdentity(String subject, String email, boolean emailVerified, String name, String pictureUrl) {
}
