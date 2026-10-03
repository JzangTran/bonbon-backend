package com.bonbon.backend.common.storage;

import java.time.Duration;

/**
 * Files outside the database. PRIVATE objects (identity documents, business licences) are only ever
 * handed out as short-lived signed URLs; PUBLIC objects (menu photos, avatars) have a stable URL.
 * The database keeps the object key, never the file.
 */
public interface ObjectStorage {

    /** Stores {@code file} under a fresh key below {@code prefix}; returns that key. */
    String put(Visibility visibility, String prefix, ValidatedFile file);

    /** A URL that reads a private object until {@code ttl} passes. */
    String signedUrl(String key, Duration ttl);

    /** The permanent URL of a public object. */
    String publicUrl(String key);

    void delete(Visibility visibility, String key);

    enum Visibility { PRIVATE, PUBLIC }
}
