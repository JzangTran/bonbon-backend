/**
 * Identities, roles, permissions and tokens: registration, email verification, login, logout,
 * password recovery and role switching. Owns users, user_roles, role_permissions and the token tables.
 */
@ApplicationModule(displayName = "Authentication")
package com.bonbon.backend.authentication;

import org.springframework.modulith.ApplicationModule;
