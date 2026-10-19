package com.bonbon.backend.common.geo;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Vietnam's administrative units after the 2025 merger: 34 provinces and cities, and their communes and wards (no districts).
 * The list is a file in the application (geo/vn-admin-units.json, from provinces.open-api.vn v2, fetched 2026-10-19), so
 * nothing outside is called at run time. Addresses are stored with names, which stay readable if codes change again; the
 * codes only tie a form's two selects together and are checked here.
 */
@Component
public class AdministrativeUnits {

    public record Province(int code, String name, String type) {
    }

    public record Ward(int code, String name, String type) {
    }

    record WardFile(int code, String name, String type) {
    }

    record ProvinceFile(int code, String name, String type, List<WardFile> wards) {
    }

    record DataFile(List<ProvinceFile> provinces) {
    }

    private final List<Province> provinces;
    private final Map<Integer, List<Ward>> wardsByProvince = new HashMap<>();

    public AdministrativeUnits() {
        DataFile data;
        try (InputStream in = new ClassPathResource("geo/vn-admin-units.json").getInputStream()) {
            data = JsonMapper.builder().build().readValue(in, DataFile.class);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read geo/vn-admin-units.json", e);
        }
        this.provinces = data.provinces().stream().map(p -> new Province(p.code(), p.name(), p.type())).toList();
        for (ProvinceFile p : data.provinces()) {
            wardsByProvince.put(p.code(), p.wards().stream().map(w -> new Ward(w.code(), w.name(), w.type())).toList());
        }
    }

    public List<Province> provinces() {
        return provinces;
    }

    public Optional<Province> province(int code) {
        return provinces.stream().filter(p -> p.code() == code).findFirst();
    }

    /** Empty when the province does not exist. */
    public Optional<List<Ward>> wards(int provinceCode) {
        return Optional.ofNullable(wardsByProvince.get(provinceCode));
    }

    public Optional<Ward> ward(int provinceCode, int wardCode) {
        return wards(provinceCode).flatMap(list -> list.stream().filter(w -> w.code() == wardCode).findFirst());
    }

    /** A province by the name a map provider gives ("Hà Nội", "Thành phố Hà Nội"); accents, case and the kind prefix do not matter. */
    public Optional<Province> findProvince(String name) {
        String wanted = plain(name);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        return provinces.stream().filter(p -> plain(p.name()).equals(wanted)).findFirst();
    }

    /** A ward of a province by the name a map provider gives ("Tây Mỗ", "Phường Tây Mỗ"). */
    public Optional<Ward> findWard(int provinceCode, String name) {
        String wanted = plain(name);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        return wards(provinceCode).flatMap(list -> list.stream().filter(w -> plain(w.name()).equals(wanted)).findFirst());
    }

    /** Lower case, no accents, no leading kind word (thành phố, tỉnh, phường, xã, đặc khu, thị trấn). */
    public static String plain(String name) {
        if (name == null) {
            return "";
        }
        String s = Normalizer.normalize(name.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace('đ', 'd');
        for (String prefix : new String[] {"thanh pho trung uong ", "thanh pho ", "tinh ", "phuong ", "xa ", "dac khu ", "thi tran "}) {
            if (s.startsWith(prefix)) {
                return s.substring(prefix.length()).strip();
            }
        }
        return s;
    }
}
