package com.microservice.rca.diagnosis.repository;

import com.microservice.rca.diagnosis.domain.FaultEvent;
import com.microservice.rca.diagnosis.domain.FaultEventStatus;
import java.time.Instant;
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
               set e.status = :target, e.updatedAt = :now
             where e.id = :eventId and e.status = :expected
            """)
    int updateStatusIfCurrent(@Param("eventId") UUID eventId,
                              @Param("expected") FaultEventStatus expected,
                              @Param("target") FaultEventStatus target,
                              @Param("now") Instant now);
}
