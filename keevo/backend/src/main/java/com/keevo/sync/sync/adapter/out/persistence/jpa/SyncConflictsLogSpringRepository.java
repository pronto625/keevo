package com.keevo.sync.sync.adapter.out.persistence.jpa;

import com.keevo.sync.sync.adapter.out.persistence.entity.SyncConflictsLogJpaEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SyncConflictsLogSpringRepository
        extends JpaRepository<SyncConflictsLogJpaEntity, String> {

    List<SyncConflictsLogJpaEntity> findAllByOrderByResolvedAtDesc(Pageable pageable);

    List<SyncConflictsLogJpaEntity> findByEntityId(String entityId);
}
