/**
 * Identity domain events, published to other modules as {@code "identity :: events"} (Story 12.2d).
 *
 * <p>The account / registration events of {@code identity.auth.domain.model} and the onboarding
 * events of {@code identity.onboarding.domain.model} live next to their models: each carries a
 * type-level {@code @NamedInterface("events")}, which Spring Modulith 1.4 merges into this
 * package-level interface (two package-level declarations would not be merged — see 12.2a). A new
 * event in one of those packages must carry the annotation too.
 */
@org.springframework.modulith.NamedInterface("events")
package com.keevo.identity.employee.domain.event;
