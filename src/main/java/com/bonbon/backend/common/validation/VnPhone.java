package com.bonbon.backend.common.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * A Vietnamese phone number that really exists, checked against Google's libphonenumber metadata rather than a digit count: ‘0912345678’
 * and ‘+84912345678’ pass, ‘0000000000’, ‘0123456789’ (a prefix retired in 2018) and numbers with spaces do not. {@code null} passes
 * (combine with {@code @NotBlank} when the field is required); an empty string passes only when {@link #allowEmpty()}.
 */
@Documented
@Constraint(validatedBy = VnPhoneValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface VnPhone {

    enum Kind {
        /** A mobile number: customers and delivery recipients must be reachable by phone call and app. */
        MOBILE,
        /** A mobile or a fixed line, for a shop's counter phone. */
        ANY
    }

    Kind kind() default Kind.MOBILE;

    /** An empty string means: remove the number, on some update requests. */
    boolean allowEmpty() default false;

    String message() default "Số điện thoại Việt Nam không hợp lệ";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
