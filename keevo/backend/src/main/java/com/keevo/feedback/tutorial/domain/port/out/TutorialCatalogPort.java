package com.keevo.feedback.tutorial.domain.port.out;

import com.keevo.feedback.tutorial.domain.model.Tutorial;

import java.util.List;

/**
 * TutorialCatalogPort — source of the configured tutorial entries (v1s-14-6). Entries are
 * returned as configured (URLs not validated yet).
 */
public interface TutorialCatalogPort {

    List<Tutorial> findAll();
}
