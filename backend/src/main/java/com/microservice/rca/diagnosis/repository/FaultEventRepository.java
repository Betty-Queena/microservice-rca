package com.microservice.rca.diagnosis.repository;

import com.microservice.rca.diagnosis.domain.FaultEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FaultEventRepository extends JpaRepository<FaultEvent, UUID>,
        JpaSpecificationExecutor<FaultEvent> {
}
