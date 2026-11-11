package com.bonbon.backend.operations.appconfig.service;

/** Comparing app versions part by part as numbers, so 1.10.0 is newer than 1.9.0 (a string comparison gets that wrong). */
final class AppVersions {

    private AppVersions() {
    }

    /**
     * True when {@code version} is older than {@code than}. A version that cannot be read (missing, or not numbers separated by dots,
     * with an optional suffix after a dash or plus) is never reported as older: an unknown client is not locked out.
     */
    static boolean below(String version, String than) {
        int[] a = parse(version);
        int[] b = parse(than);
        if (a == null || b == null) {
            return false;
        }
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x < y;
            }
        }
        return false;
    }

    static int[] parse(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String core = version.strip().split("[-+]", 2)[0];
        String[] parts = core.split("\\.");
        int[] numbers = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                numbers[i] = Integer.parseInt(parts[i]);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return numbers;
    }
}
