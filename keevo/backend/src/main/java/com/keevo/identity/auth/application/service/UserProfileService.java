package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserProfileData;
import com.keevo.identity.auth.domain.port.in.GetUserProfileUseCase;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * UserProfileService — Facade for user profile retrieval (Story 8.6 AC3, AC4).
 *
 * <p>GoF Facade pattern: hides coordination of UserRepository, EmployeeRepository
 * and StoreRepository behind a single {@link #execute} method.
 *
 * <p>EMPLOYEE: firstName, lastName, storeId, storeName resolved from employee + store records.
 * OWNER: no employee record — all optional fields are null.
 *
 * <p>Open/Closed: adding new profile fields requires only extending {@link UserProfileData}
 * and this service — the port and controller remain unchanged.
 */
@Service
public class UserProfileService implements GetUserProfileUseCase {

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final StoreRepository storeRepository;

    public UserProfileService(UserRepository userRepository,
                               EmployeeRepository employeeRepository,
                               StoreRepository storeRepository) {
        this.userRepository = userRepository;
        this.employeeRepository = employeeRepository;
        this.storeRepository = storeRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfileData execute(UUID userId, String tenantId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new DomainException(ErrorCode.INVALID_CREDENTIALS));

        Optional<Employee> employeeOpt = employeeRepository.findByUserId(userId);

        if (employeeOpt.isPresent()) {
            Employee emp = employeeOpt.get();
            String storeName = storeRepository.findById(emp.getStoreId())
                    .map(s -> s.name())
                    .orElse(null);
            return new UserProfileData(
                    user.getId(),
                    user.getPhoneNumber(),
                    "EMPLOYEE",
                    emp.getFirstName(),
                    emp.getLastName(),
                    emp.getStoreId().toString(),
                    storeName
            );
        }

        return new UserProfileData(
                user.getId(),
                user.getPhoneNumber(),
                "OWNER",
                null,
                null,
                null,
                null
        );
    }
}
