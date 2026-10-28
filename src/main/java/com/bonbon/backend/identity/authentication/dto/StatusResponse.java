package com.bonbon.backend.identity.authentication.dto;

/** A machine-readable outcome code for flows that return no data (e.g. VERIFIED, ALREADY_VERIFIED). */
public record StatusResponse(String status) {
}
