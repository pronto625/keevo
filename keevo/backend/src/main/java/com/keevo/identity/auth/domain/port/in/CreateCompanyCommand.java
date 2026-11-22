package com.keevo.identity.auth.domain.port.in;

import com.keevo.identity.auth.domain.model.DeviceInfo;

/**
 * Story 19.2 — company creation by a signed-in account without company.
 *
 * @param loginToken login-step-1 token ({@code scope=login_pending})
 * @param name       full name of the owner (one field, first and last name together)
@@    the app's device id and label (optional)
 */
public record CreateCompanyCommand(String loginToken, String name, DeviceInfo device) {}
