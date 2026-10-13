package com.keevo.admin.tenant.domain.model;

import java.time.LocalDate;

/**
 * AdminEmployeeInfo — employee summary for the tenant detail drawer (no identifier).
 *
 * @param name           trimmed first + last name from {@code <schema>.employees}; « Propriétaire »
 *                       for an owner without a stored name, « Employé » for an employee without one
 * @param role           membership role (OWNER | EMPLOYEE), labelled by the dashboard
 * @param lastActivityAt latest {@code public.user_daily_activity.activity_date} of the user in this
 *                       tenant; null when never active
 */
public record AdminEmployeeInfo(
        String name,
        String role,
        LocalDate lastActivityAt
) {}
