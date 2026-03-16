package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.port.out.RefreshTokenRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.model.CreateEmployeeResult;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.TempPasswordFactory;
import com.keevo.identity.employee.domain.port.in.RegeneratePasswordCommand;
import com.keevo.identity.employee.domain.port.in.RegeneratePasswordUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RegeneratePasswordService — Generates a new temporary password for an employee.
 *
 * <p>Updates the bcrypt hash in public.users, sets passwordChangeRequired = true,
 * and revokes existing refresh tokens to force re-login.
 */
@Service
public class RegeneratePasswordService implements RegeneratePasswordUseCase {

    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;

    public RegeneratePasswordService(EmployeeRepository employeeRepository,
                                      UserRepository userRepository,
                                      RefreshTokenRepository refreshTokenRepository,
                                      PasswordEncoder passwordEncoder) {
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public CreateEmployeeResult execute(RegeneratePasswordCommand command) {
        // 1. Load employee
        Employee employee = employeeRepository.findById(command.employeeId())
                .orElseThrow(() -> new DomainException(ErrorCode.EMPLOYEE_NOT_FOUND));

        // 2. Generate new temp password
        String tempPassword = TempPasswordFactory.generate();
        String hash = passwordEncoder.encode(tempPassword);

        // 3. Update password hash in public.users
        var user = userRepository.findById(employee.getUserId())
                .orElseThrow(() -> new DomainException(ErrorCode.USER_NOT_FOUND));
        userRepository.save(user.withPasswordHash(hash));

        // 4. Set passwordChangeRequired = true
        Employee updated = employeeRepository.updatePasswordChangeRequired(
                command.employeeId(), true);

        // 5. Revoke refresh tokens to force re-login
        refreshTokenRepository.revokeAllByUserId(employee.getUserId());

        return new CreateEmployeeResult(updated, tempPassword);
    }
}
