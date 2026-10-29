package archfixture.domain;

/**
 * Rule (b) fixtures for {@code KeevoArchitectureRulesSelfCheckTest} (Story 12.6b). Deliberately OUTSIDE
 * {@code com.keevo}: Spring Boot / JPA scanning (rooted at com.keevo) never picks them up.
 */
@jakarta.persistence.Entity
class FixtureEntity {
    @jakarta.persistence.Id
    Long id;
}

@jakarta.persistence.Embeddable
class FixtureEmbeddable {
}

@jakarta.persistence.MappedSuperclass
class FixtureMappedSuperclass {
}

@org.springframework.stereotype.Repository
class FixtureRepositoryAdapter {
}

interface FixtureSpringDataRepository extends org.springframework.data.repository.Repository<FixtureEntity, Long> {
}
