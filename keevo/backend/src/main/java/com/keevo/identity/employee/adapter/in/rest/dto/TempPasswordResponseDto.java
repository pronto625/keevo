package com.keevo.identity.employee.adapter.in.rest.dto;

public record TempPasswordResponseDto(
        EmployeeResponseDto employee,
        String temporaryPassword
) {}
