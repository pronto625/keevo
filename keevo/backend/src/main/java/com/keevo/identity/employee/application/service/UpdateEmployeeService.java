package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.event.EmployeeUpdatedEvent;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.in.UpdateEmployeeCommand;
import com.keevo.identity.employee.domain.port.in.UpdateEmployeeUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * UpdateEmployeeService — Updates an employee's profile (first name, last name,
 * phone number, store ID). Partial update: only non-null fields are applied.
 *
 * <p>Story 14.11 — AC1.
 */
@Service
public class UpdateEmployeeService implements UpdateEmployeeUseCase {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9]{8,15}$");

    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ApplicationEventPublisher eventPublisher;

    public UpdateEmployeeService(EmployeeRepository employeeRepository,
                                  UserRepository userRepository,
                                  RefreshTokenRepository refreshTokenRepository,
                                  ApplicationEventPublisher eventPublisher) {
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public Employee execute(UpdateEmployeeCommand command) {
        // 1. Load employee
        Employee employee = employeeRepository.findById(command.employeeId())
                .orElseThrow(() -> new DomainException(ErrorCode.EMPLOYEE_NOT_FOUND));

        // 2. Load user
        User user = userRepository.findById(employee.getUserId())
                .orElseThrow(() -> new DomainException(ErrorCode.USER_NOT_FOUND));

        List<String> fieldsChanged = new ArrayList<>();
        boolean nameChanged = false;
        boolean phoneChanged = false;
        boolean storeChanged = false;
        String newFirstName = employee.getFirstName();
        String newLastName = employee.getLastName();

        // 3. Apply firstName/lastName if provided
        if (command.firstName() != null && !command.firstName().equals(employee.getFirstName())) {
            newFirstName = command.firstName();
            nameChanged = true;
            fieldsChanged.add("firstName");
        }
        if (command.lastName() != null && !command.lastName().equals(employee.getLastName())) {
            newLastName = command.lastName();
            nameChanged = true;
            fieldsChanged.add("lastName");
        }
        if (nameChanged) {
            employee = employeeRepository.updateProfile(employee.getId(), newFirstName, newLastName);
        }

        // 4. Apply phoneNumber if provided and different
        if (command.phoneNumber() != null) {
            if (!PHONE_PATTERN.matcher(command.phoneNumber()).matches()) {
                throw new DomainException(ErrorCode.VALIDATION_FAILED, "Invalid phone number format");
            }
            if (!command.phoneNumber().equals(user.getPhoneNumber())) {
                // Check uniqueness cross-tenant
                if (userRepository.existsByPhoneNumber(command.phoneNumber())) {
                    throw new DomainException(ErrorCode.PHONE_ALREADY_REGISTERED);
                }
                user = user.withPhoneNumber(command.phoneNumber());
                user = userRepository.save(user);
                phoneChanged = true;
                fieldsChanged.add("phoneNumber");
            }
        }

        // 5. Apply storeId if provided and different
        if (command.storeId() != null && !command.storeId().equals(employee.getStoreId())) {
            employee = employeeRepository.updateStoreId(employee.getId(), command.storeId());
            refreshTokenRepository.revokeAllByUserId(employee.getUserId());
            storeChanged = true;
            fieldsChanged.add("storeId");
        }

        // 6. Publish event if anything changed
        if (!fieldsChanged.isEmpty()) {
            String tenantSchema = TenantContext.getCurrentTenant();
            eventPublisher.publishEvent(new EmployeeUpdatedEvent(
                    command.actorId(), tenantSchema, employee.getId(),
                    fieldsChanged, Instant.now()));
        }

        return employee;
    }
}
