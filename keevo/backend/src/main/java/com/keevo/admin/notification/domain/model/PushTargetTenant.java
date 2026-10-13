package com.keevo.admin.notification.domain.model;

import java.util.UUID;

/** A company reached by a global push: id (logs only) and its schema (device tokens). */
public record PushTargetTenant(UUID id, String schemaName) {}
