package com.bonbon.backend.common.openapi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One business error an endpoint can answer with, for the API reference: the HTTP status, the stable {@code code}
 * clients switch on, and when it happens. Validation (400), authentication (401), permission (403) and server errors
 * (500) are added to every operation automatically, so only the endpoint's own rules are listed here.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(ApiErrors.class)
public @interface ApiError {

    int status();

    String code();

    /** When it happens, in Vietnamese, as a client developer would want to read it. */
    String when();
}
