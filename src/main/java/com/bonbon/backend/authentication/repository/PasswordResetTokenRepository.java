package com.bonbon.backend.authentication.repository;

import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.authentication.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from PasswordResetToken t where t.userId = :userId")
    int deleteAllForUser(@Param("userId") UUID userId);
}
