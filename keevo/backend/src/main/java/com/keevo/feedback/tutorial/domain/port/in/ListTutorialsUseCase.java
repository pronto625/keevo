package com.keevo.feedback.tutorial.domain.port.in;

import com.keevo.feedback.tutorial.domain.model.Tutorial;

import java.util.List;

/**
 * ListTutorialsUseCase — FR7 tutorial catalog (v1s-14-6), readable by any authenticated user.
 * Only http(s) URLs are exposed; any other value is returned blank.
 */
public interface ListTutorialsUseCase {

    List<Tutorial> list();
}
