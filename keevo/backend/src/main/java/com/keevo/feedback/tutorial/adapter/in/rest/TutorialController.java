package com.keevo.feedback.tutorial.adapter.in.rest;

import com.keevo.feedback.tutorial.adapter.in.rest.dto.TutorialResponseDto;
import com.keevo.feedback.tutorial.domain.port.in.ListTutorialsUseCase;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * TutorialController — FR7 « Aide & Tutoriels » catalog (v1s-14-6). Any authenticated user
 * (OWNER or EMPLOYEE) can read it.
 */
@Tag(name = "Help", description = "Video tutorials")
@EntitlementExempt("core: tutoriels")
@RestController
@RequestMapping("/api/v1/help")
public class TutorialController {

    private final ListTutorialsUseCase listTutorialsUseCase;

    public TutorialController(ListTutorialsUseCase listTutorialsUseCase) {
        this.listTutorialsUseCase = listTutorialsUseCase;
    }

    @Operation(summary = "List the French video tutorials (external links)")
    @GetMapping("/tutorials")
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    public ResponseEntity<ApiResponseWrapper<List<TutorialResponseDto>>> list() {
        return ResponseEntity.ok(ApiResponseWrapper.ok(listTutorialsUseCase.list().stream()
                .map(TutorialResponseDto::fromDomain).toList()));
    }
}
