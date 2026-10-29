/**
 * Public API of the {@code feedback} module (Story 12.2e): user feedback submission and the
 * SUPER_ADMIN inbox.
 *
 * <p>Only ports and immutable records live here. The implementation is an internal, package-private
 * adapter ({@code feedback.feedback.adapter.in.api}) delegating to the existing use cases; it carries
 * no transaction of its own (the submit / handle use cases are {@code @Transactional} REQUIRED and
 * join the caller's transaction).
 *
 * <p>Feedback is a platform-wide ({@code public} schema) table: no method is tenant-scoped. The
 * submitting tenant is data ({@link com.keevo.feedback.api.SubmitFeedbackInput#tenantSchema()}),
 * checked against the bound tenant when there is one. The inbox port is admin-only: callers enforce
 * SUPER_ADMIN.
 *
 * <p>Other modules depend on {@code "feedback :: api"}, never on feedback internals.
 */
@org.springframework.modulith.NamedInterface("api")
package com.keevo.feedback.api;
