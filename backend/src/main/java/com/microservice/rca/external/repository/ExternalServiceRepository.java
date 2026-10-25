package com.microservice.rca.external.repository;

import com.microservice.rca.external.domain.ExternalService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalServiceRepository extends JpaRepository<ExternalService, UUID> {

    Optional<ExternalService> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    List<ExternalService> findAllByOrderByCreatedAtDesc();

    List<ExternalService> findAllByEnabledTrueOrderByNameAsc();
}
