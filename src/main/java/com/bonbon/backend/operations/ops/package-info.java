/**
 * What the infrastructure administrators watch: whether the parts the platform stands on answer (database, Redis) and how the
 * running instance is doing. Read only; the settings an operator changes (maintenance, app versions) live in appconfig.
 */
@NamedInterface("ops")
package com.bonbon.backend.operations.ops;

import org.springframework.modulith.NamedInterface;
