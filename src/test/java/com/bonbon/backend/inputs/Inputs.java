package com.bonbon.backend.inputs;

import java.util.Set;
import java.util.TreeSet;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * Helper for the input unit tests: one test class per use case, each a table of input combinations and the expected outcome, the same
 * tables as the report (docs/report, chapter 7, test tables). The outcome is OK or the fields that are refused, joined by +.
 */
public final class Inputs {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private Inputs() {
    }

    /** OK, or the refused fields in alphabetical order (list indexes dropped: items[0].quantity becomes items.quantity). */
    public static String verdict(Object request) {
        Set<String> fields = new TreeSet<>();
        for (ConstraintViolation<Object> v : VALIDATOR.validate(request)) {
            String path = v.getPropertyPath().toString().replaceAll("\\[\\d*]", "").replace(".<list element>", "")
                    .replace("<list element>", "");
            fields.add(path.endsWith(".") ? path.substring(0, path.length() - 1) : path);
        }
        return fields.isEmpty() ? "OK" : String.join("+", fields);
    }

    /**
     * Lets a table cell stand for a long value: {@code letters:101} is 101 letters, {@code digits:20} is 20 digits; anything else is
     * returned as written (an empty cell is null, {@code ''} is the empty string).
     */
    public static String expand(String cell) {
        if (cell == null) {
            return null;
        }
        if (cell.startsWith("letters:")) {
            return "a".repeat(Integer.parseInt(cell.substring(8)));
        }
        if (cell.startsWith("digits:")) {
            return "7".repeat(Integer.parseInt(cell.substring(7)));
        }
        return cell;
    }
}
