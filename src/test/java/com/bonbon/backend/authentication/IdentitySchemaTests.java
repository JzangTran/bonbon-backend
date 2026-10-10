package com.bonbon.backend.authentication;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.RolePermissionRepository;
import com.bonbon.backend.authentication.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class IdentitySchemaTests {

    @Autowired
    UserRepository users;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    JdbcClient jdbc;

    @Test
    void userWithRolesRoundTrips() {
        User user = new User("lan@example.com", "hash", "Lan");
        user.addRole(Role.CUSTOMER);
        user.addRole(Role.SELLER);
        users.saveAndFlush(user);

        User found = users.findByEmail("LAN@Example.com").orElseThrow();
        assertThat(found.getRoles()).containsExactlyInAnyOrder(Role.CUSTOMER, Role.SELLER);
        assertThat(found.isEmailVerified()).isFalse();
        assertThat(found.getTokensValidAfter()).isNotNull();
    }

    @Test
    void emailIsUniqueIgnoringCase() {
        users.saveAndFlush(new User("minh@example.com", "hash", "Minh"));
        assertThatThrownBy(() -> users.saveAndFlush(new User("MINH@example.com", "hash", "Minh 2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void adminCannotHoldAnotherRole() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, email, name) VALUES (:id, 'admin@example.com', 'Admin')")
                .param("id", id).update();
        jdbc.sql("INSERT INTO user_roles (user_id, role) VALUES (:id, 'ADMIN')").param("id", id).update();

        assertThatThrownBy(() -> jdbc.sql("INSERT INTO user_roles (user_id, role) VALUES (:id, 'SELLER')")
                .param("id", id).update())
                .hasMessageContaining("admin identity cannot hold other roles");
    }

    @Test
    void sellerCannotBecomeAdmin() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, email, name) VALUES (:id, 'shop@example.com', 'Shop')")
                .param("id", id).update();
        jdbc.sql("INSERT INTO user_roles (user_id, role) VALUES (:id, 'SELLER')").param("id", id).update();

        assertThatThrownBy(() -> jdbc.sql("INSERT INTO user_roles (user_id, role) VALUES (:id, 'ADMIN')")
                .param("id", id).update())
                .hasMessageContaining("admin identity cannot hold other roles");
    }

    @Test
    void seededPermissionsAreExactlyTheCatalog() {
        Set<String> seeded = Set.copyOf(rolePermissions.findAllPermissionCodes());
        Set<String> catalog = Arrays.stream(Permission.values()).map(Permission::code).collect(Collectors.toSet());
        assertThat(seeded).isEqualTo(catalog);
    }

    @Test
    void customerGetsOnlyCustomerPermissions() {
        List<String> perms = rolePermissions.findPermissionCodes("CUSTOMER");
        assertThat(perms).containsExactlyInAnyOrder(
                "order:create", "order:cancel", "order:report", "review:create", "conversation:read", "conversation:write");
    }
}
