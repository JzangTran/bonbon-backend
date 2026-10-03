package com.bonbon.backend.authentication.repository;

import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.authentication.entity.AuthProvider;
import com.bonbon.backend.authentication.entity.UserAuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAuthProviderRepository extends JpaRepository<UserAuthProvider, UserAuthProvider.Key> {

    @Query("select p from UserAuthProvider p where p.key.provider = :provider and p.providerId = :providerId")
    Optional<UserAuthProvider> findByProviderIdentity(@Param("provider") AuthProvider provider,
            @Param("providerId") String providerId);

    @Query("select count(p) > 0 from UserAuthProvider p where p.key.userId = :userId and p.key.provider = :provider")
    boolean isLinked(@Param("userId") UUID userId, @Param("provider") AuthProvider provider);
}
