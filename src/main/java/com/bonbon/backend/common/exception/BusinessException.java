package com.bonbon.backend.common.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * A rule of the business was broken by the request. {@code code} is a stable, machine-readable
 * identifier (e.g. {@code ORDER_ALREADY_CONFIRMED}) that clients may switch on; the message is for people.
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static BusinessException notFound(String code, String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, message);
    }

    public static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    public static BusinessException unprocessable(String code, String message) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    /** Extra problem-detail member the client needs to react (e.g. which step comes next). */
    public BusinessException withProperty(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    public Map<String, Object> getProperties() {
        return Map.copyOf(properties);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
