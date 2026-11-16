package com.bonbon.backend.common.validation;

import java.util.regex.Pattern;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class VnPhoneValidator implements ConstraintValidator<VnPhone, String> {

    /** The stored shape: digits only after 0 or +84, so numbers compare and display the same everywhere. */
    private static final Pattern SHAPE = Pattern.compile("^(0|\\+84)\\d{8,10}$");
    private static final PhoneNumberUtil PHONES = PhoneNumberUtil.getInstance();

    private VnPhone.Kind kind;
    private boolean allowEmpty;

    @Override
    public void initialize(VnPhone annotation) {
        kind = annotation.kind();
        allowEmpty = annotation.allowEmpty();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (value.isEmpty()) {
            return allowEmpty;
        }
        return isValid(value, kind);
    }

    /** Shared with services that check a number outside a request body. */
    public static boolean isValid(String value, VnPhone.Kind kind) {
        if (value == null || !SHAPE.matcher(value).matches()) {
            return false;
        }
        try {
            PhoneNumber number = PHONES.parse(value, "VN");
            if (number.getCountryCode() != 84 || !PHONES.isValidNumberForRegion(number, "VN")) {
                return false;
            }
            PhoneNumberType type = PHONES.getNumberType(number);
            return switch (kind) {
                case MOBILE -> type == PhoneNumberType.MOBILE || type == PhoneNumberType.FIXED_LINE_OR_MOBILE;
                case ANY -> type == PhoneNumberType.MOBILE || type == PhoneNumberType.FIXED_LINE || type == PhoneNumberType.FIXED_LINE_OR_MOBILE;
            };
        } catch (NumberParseException e) {
            return false;
        }
    }
}
