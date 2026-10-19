package com.bonbon.backend;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The address a test shop is given in step 1 of the wizard: a ward of Hà Nội and a point about 300 m from the fake geocoder's
 * origin, so the customers the tests create (who use the fake geocoder's places) are inside the shop's radius.
 */
public final class ShopAddress {

    /** Thành phố Hà Nội. */
    public static final int PROVINCE = 1;
    /** Phường Hoàn Kiếm, one of Hà Nội's wards in the shipped list. */
    public static final int WARD = 70;
    public static final String DETAIL = "Số 1 Phố Test";
    public static final double LAT = 21.0312;
    public static final double LNG = 105.8542;

    private ShopAddress() {
    }

    /** The address fields alone, to add to a step 1 payload. */
    public static Map<String, Object> fields() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provinceCode", PROVINCE);
        m.put("wardCode", WARD);
        m.put("addressDetail", DETAIL);
        m.put("lat", LAT);
        m.put("lng", LNG);
        return m;
    }

    /** A complete step 1 payload. */
    public static Map<String, Object> step1(String name, String phone, String email) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("phone", phone);
        m.put("email", email);
        m.putAll(fields());
        return m;
    }
}
