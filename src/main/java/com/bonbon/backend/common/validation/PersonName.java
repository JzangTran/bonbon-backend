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
 * A person's name as written on papers: letters of any language (so ‘Nguyễn Thị Ánh’), with single spaces, dots, apostrophes or hyphens
 * between them, at least two letters, no digits or symbols. Leading and trailing spaces are ignored. {@code null} passes; pair with
 * {@code @NotBlank} when required.
 */
@Documented
@Constraint(validatedBy = PersonName.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PersonName {

    String message() default "Họ tên chỉ gồm chữ cái và khoảng trắng";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<PersonName, String> {

        private static final Pattern NAME = Pattern.compile("^[\\p{L}\\p{M}]+(?:[ .'\\-]{1,2}[\\p{L}\\p{M}]+)*\\.?$");

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || isValid(value);
        }

        public static boolean isValid(String value) {
            String v = value.trim();
            if (v.isEmpty()) {
                return true; // blank is @NotBlank's business, so an optional field may be sent empty
            }
            long letters = v.codePoints().filter(Character::isLetter).count();
            return letters >= 2 && NAME.matcher(v).matches();
        }
    }
}
