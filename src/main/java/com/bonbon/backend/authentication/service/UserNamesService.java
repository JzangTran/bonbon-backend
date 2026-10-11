package com.bonbon.backend.authentication.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.authentication.UserNames;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UserNamesService implements UserNames {

    private final JdbcClient jdbc;

    UserNamesService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> names(Collection<UUID> userIds) {
        Map<UUID, String> result = new HashMap<>();
        if (!userIds.isEmpty()) {
            jdbc.sql("select id, name from users where id in (:ids) and name is not null")
                    .param("ids", userIds)
                    .query((rs, n) -> {
                        result.put(rs.getObject("id", UUID.class), rs.getString("name"));
                        return null;
                    }).list();
        }
        return result;
    }
}
