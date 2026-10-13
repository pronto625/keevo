package com.keevo.admin.notification.domain.model;

import java.util.UUID;

/** A company as the push features need it: id, business name and current plan ({@code public.tenants}). */
public record PushTenantRef(UUID id, String name, String planType) {}
