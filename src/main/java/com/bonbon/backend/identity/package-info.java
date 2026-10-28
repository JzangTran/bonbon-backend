/**
 * Who uses bonbon: accounts, sign-in (email, Google), administrator sign-in, roles and permissions, profiles and delivery addresses,
 * and the legal documents people consent to.
 * <p>
 * Its parts (authentication, adminauthentication, account, legal) are named interfaces: their top-level types are what other modules may use; their
 * sub-packages are internal to this module.
 */
@ApplicationModule(displayName = "Identity & Access")
package com.bonbon.backend.identity;

import org.springframework.modulith.ApplicationModule;
