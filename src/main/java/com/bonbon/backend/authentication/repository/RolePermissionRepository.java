package com.bonbon.backend.authentication.repository;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** role_permissions is a plain mapping table, read with SQL rather than mapped as an entity. */
@Repository
public class RolePermissionRepository {

    private final JdbcClient jdbc;

    public RolePermissionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> findPermissionCodes(String role) {
        return jdbc.sql("SELECT permission FROM role_permissions WHERE role = :role ORDER BY permission")
                .param("role", role)
                .query(String.class)
                .list();
    }

    public List<String> findAllPermissionCodes() {
        return jdbc.sql("SELECT DISTINCT permission FROM role_permissions").query(String.class).list();
    }
}
