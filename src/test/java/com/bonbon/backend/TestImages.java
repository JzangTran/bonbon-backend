package com.bonbon.backend;

import java.util.Base64;

/** Pictures that pass the upload checks (a real, well-formed 1 x 1 PNG), for tests that upload one. */
public final class TestImages {

    public static final byte[] PNG = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    private TestImages() {
    }
}
