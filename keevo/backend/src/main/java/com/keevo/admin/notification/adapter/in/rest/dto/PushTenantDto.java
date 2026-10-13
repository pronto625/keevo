package com.keevo.admin.notification.adapter.in.rest.dto;

import java.util.UUID;

/** A company of the picker « Un commerce »: id (request value only, never displayed) and business name. */
public record PushTenantDto(UUID id, String name) {}
