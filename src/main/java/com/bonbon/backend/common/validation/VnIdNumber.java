package com.bonbon.backend.common.validation;

import java.time.Year;
import java.util.Optional;
import java.util.Set;

/**
 * Checks on Vietnamese identity numbers beyond their length.
 * <p>
 * A 12-digit citizen identity number (CCCD, also the personal tax code since 1 July 2025) is built as: 3 digits for the province where
 * the birth was registered, 1 digit for sex and century (0/1 for men/women born 1900–1999, 2/3 for 2000–2099), 2 digits for the year of
 * birth, then 6 random digits. So the first three digits must be one of the 63 province codes, the fourth 0–3, and the year must not lie
 * in the future. The old identity card (CMND) has 9 digits with no structure to check, or 12 digits on the 2012–2016 card, which is
 * built like a CCCD number.
 */
public final class VnIdNumber {

    /** Province codes used in CCCD numbers (the 63 provinces before the 2025 merger; merged provinces kept existing numbers). */
    static final Set<String> PROVINCES = Set.of(
            "001", "002", "004", "006", "008", "010", "011", "012", "014", "015", "017", "019", "020", "022", "024", "025", "026", "027",
            "030", "031", "033", "034", "035", "036", "037", "038", "040", "042", "044", "045", "046", "048", "049", "051", "052", "054",
            "056", "058", "060", "062", "064", "066", "067", "068", "070", "072", "074", "075", "077", "079", "080", "082", "083", "084",
            "086", "087", "089", "091", "092", "093", "094", "095", "096");

    private VnIdNumber() {
    }

    /** Why a CCCD number cannot be right, or empty when it can. */
    public static Optional<String> citizenIdProblem(String number) {
        if (number == null || !number.matches("\\d{12}")) {
            return Optional.of("Số CCCD gồm 12 chữ số.");
        }
        if (!PROVINCES.contains(number.substring(0, 3))) {
            return Optional.of("Ba số đầu của CCCD là mã tỉnh nơi đăng ký khai sinh; mã " + number.substring(0, 3) + " không tồn tại.");
        }
        int sexCentury = number.charAt(3) - '0';
        if (sexCentury > 3) {
            return Optional.of("Số thứ tư của CCCD (giới tính và thế kỷ sinh) phải từ 0 đến 3.");
        }
        int year = (sexCentury < 2 ? 1900 : 2000) + Integer.parseInt(number.substring(4, 6));
        if (year > Year.now().getValue()) {
            return Optional.of("Năm sinh trong số CCCD nằm ở tương lai; hãy kiểm tra lại.");
        }
        return Optional.empty();
    }

    /** Why an old identity card number cannot be right, or empty when it can. */
    public static Optional<String> identityCardProblem(String number) {
        if (number != null && number.matches("\\d{9}")) {
            return Optional.empty();
        }
        if (number != null && number.matches("\\d{12}")) {
            return citizenIdProblem(number).map(p -> p.replace("CCCD", "CMND 12 số"));
        }
        return Optional.of("Số CMND gồm 9 hoặc 12 chữ số.");
    }
}
