package com.bonbon.backend.authentication.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.authentication.CustomerDirectory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CustomerDirectoryService implements CustomerDirectory {

    private static final String SELECT = "select id, email, phone, name, email_verified, created_at from users ";

    private final JdbcClient jdbc;

    CustomerDirectoryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Account> byEmail(String email) {
        return jdbc.sql(SELECT + "where lower(email) = lower(:e)").param("e", email.strip()).query((rs, n) -> account(rs)).optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Account> byPhone(String phone, int limit) {
        return jdbc.sql(SELECT + "where phone = :p order by created_at, id limit :l").param("p", phone.strip()).param("l", limit).query((rs, n) -> account(rs)).list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Account> get(UUID userId) {
        return jdbc.sql(SELECT + "where id = :id").param("id", userId).query((rs, n) -> account(rs)).optional();
    }

    private Account account(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        Set<String> roles = new HashSet<>(jdbc.sql("select role from user_roles where user_id = :id").param("id", id).query(String.class).list());
        return new Account(id, rs.getString("email"), rs.getString("phone"), rs.getString("name"), roles, rs.getBoolean("email_verified"),
                rs.getTimestamp("created_at").toInstant());
    }
}
