package com.microservice.rca.diagnosis.repository;

import com.microservice.rca.diagnosis.domain.DiagnosisStatus;
import com.microservice.rca.diagnosis.domain.DiagnosisTask;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagnosisTaskRepository extends JpaRepository<DiagnosisTask, UUID> {

    List<DiagnosisTask> findByFaultEventIdOrderByCreatedAtDesc(UUID faultEventId);

    boolean existsByFaultEventIdAndStatusIn(UUID faultEventId, Collection<DiagnosisStatus> statuses);
}
