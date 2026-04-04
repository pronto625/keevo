package com.keevo.messaging.notification.domain.port.in;

public interface DeleteDeviceTokenUseCase {
    boolean delete(String token);
}
