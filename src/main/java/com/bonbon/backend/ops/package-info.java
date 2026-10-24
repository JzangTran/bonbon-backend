/**
 * What the infrastructure administrators watch: whether the parts the platform stands on answer (database, Redis) and how the
 * running instance is doing. Read only; the settings an operator changes (maintenance, app versions) live in appconfig.
 */
@ApplicationModule(displayName = "Ops")
package com.bonbon.backend.ops;

import org.springframework.modulith.ApplicationModule;
