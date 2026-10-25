package com.microservice.rca.diagnosis.repository;

import com.microservice.rca.diagnosis.domain.FaultEvent;
import com.microservice.rca.diagnosis.domain.FaultEventStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface FaultEventRepository extends JpaRepository<FaultEvent, UUID>,
        JpaSpecificationExecutor<FaultEvent> {

    /**
     * 条件更新事件状态，避免“先读取再保存”覆盖人工刚写入的 RESOLVED/CLOSED。
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update FaultEvent e
               set e.status = :target, e.updatedAt = :now, e.version = e.version + 1
             where e.id = :eventId and e.status = :expected
            """)
    int updateStatusIfCurrent(@Param("eventId") UUID eventId,
                              @Param("expected") FaultEventStatus expected,
                              @Param("target") FaultEventStatus target,
                              @Param("now") Instant now);

    /**
     * 处置验证通过后，仅当事件仍为 OPEN 时条件更新为 RESOLVED。
     * 同时写入 resolvedAt，不覆盖人工并发写入的 RESOLVED/CLOSED。
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update FaultEvent e
               set e.status = :resolved,
                   e.resolvedAt = :now,
                   e.updatedAt = :now,
                   e.version = e.version + 1
             where e.id = :eventId and e.status = :open
            """)
    int resolveIfOpen(@Param("eventId") UUID eventId,
                      @Param("open") FaultEventStatus open,
                      @Param("resolved") FaultEventStatus resolved,
                      @Param("now") Instant now);

    List<FaultEvent> findByStatus(FaultEventStatus status);
}
