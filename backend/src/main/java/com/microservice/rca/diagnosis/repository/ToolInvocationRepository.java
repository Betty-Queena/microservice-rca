package com.microservice.rca.diagnosis.repository;

import com.microservice.rca.diagnosis.domain.ToolInvocation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolInvocationRepository extends JpaRepository<ToolInvocation, UUID> {

    List<ToolInvocation> findByDiagnosisTaskIdOrderBySequenceNoAsc(UUID diagnosisTaskId);
}
