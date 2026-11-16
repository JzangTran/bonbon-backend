package com.bonbon.backend.common.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.regex.Pattern;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * An address mail can actually be sent to. Bean Validation's {@code @Email} accepts ‘an@localhost’ and ‘an@gmail’ because RFC 5322 allows
 * them; on the internet a domain needs a dot and a top-level domain of at least two letters. Also refused: spaces, two dots in a row and
 * a dot at either end of the name. {@code null} passes; pair with {@code @NotBlank} when required.
 */
@Documented
@Constraint(validatedBy = RealEmail.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RealEmail {

    String message() default "Email không hợp lệ";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<RealEmail, String> {

        private static final Pattern EMAIL = Pattern.compile(
                "^(?!\\.)(?!.*\\.\\.)[A-Za-z0-9._%+\\-]{1,64}(?<!\\.)@(?:[A-Za-z0-9](?:[A-Za-z0-9\\-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,24}$");

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || isValid(value);
        }

        public static boolean isValid(String value) {
            String v = value.trim();
            return v.length() <= 254 && EMAIL.matcher(v).matches();
        }
    }
}
