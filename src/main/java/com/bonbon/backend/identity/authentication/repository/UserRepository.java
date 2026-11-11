package com.bonbon.backend.identity.authentication.repository;

import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.identity.authentication.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmail(@Param("email") String email);

    @Query("select count(u) > 0 from User u join u.roles r where r = :role")
    boolean existsWithRole(@Param("role") com.bonbon.backend.identity.authentication.Role role);
}
