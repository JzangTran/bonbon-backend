package com.bonbon.backend.common.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.bonbon.backend.common.vietqr.VietQr;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * A bank in the Napas list that VietQR uses (short name, code or full name, any case or accents). A typo such as ‘Vietcombak’ used to be
 * saved and then silently made VietQR transfers to the shop unavailable. {@code null} and blank pass; pair with {@code @NotBlank} when
 * required.
 */
@Documented
@Constraint(validatedBy = KnownBank.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface KnownBank {

    String message() default "Hãy chọn ngân hàng trong danh sách";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<KnownBank, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.isBlank() || VietQr.bank(value).isPresent();
        }
    }
}
