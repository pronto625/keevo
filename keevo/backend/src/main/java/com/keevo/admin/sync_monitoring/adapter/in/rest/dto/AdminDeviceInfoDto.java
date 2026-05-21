package com.keevo.admin.sync_monitoring.adapter.in.rest.dto;

import com.keevo.admin.sync_monitoring.domain.model.AdminDeviceInfo;

import java.time.Instant;

public record AdminDeviceInfoDto(
        String deviceId,
        String userId,
        Instant lastPushAt,
        Instant lastPullAt,
        Instant updatedAt
) {
    public static AdminDeviceInfoDto from(AdminDeviceInfo model) {
        return new AdminDeviceInfoDto(
                model.deviceId(),
                model.userId(),
                model.lastPushAt(),
                model.lastPullAt(),
                model.updatedAt());
    }
}
