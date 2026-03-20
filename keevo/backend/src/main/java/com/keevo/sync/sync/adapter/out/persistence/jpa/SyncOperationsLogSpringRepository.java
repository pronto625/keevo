package com.keevo.sync.sync.adapter.out.persistence.jpa;

import com.keevo.sync.sync.adapter.out.persistence.entity.SyncOperationsLogJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SyncOperationsLogSpringRepository
        extends JpaRepository<SyncOperationsLogJpaEntity, String> {
}
