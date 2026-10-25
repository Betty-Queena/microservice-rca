package com.microservice.rca.external.service;

import com.microservice.rca.external.domain.ExternalService;
import com.microservice.rca.external.repository.ExternalServiceRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 外部服务注册表读取入口。工具层只读取已启用服务，不修改任何注册信息。
 */
@Component
public class ExternalServiceRegistry {

    private final ExternalServiceRepository repository;

    public ExternalServiceRegistry(ExternalServiceRepository repository) {
        this.repository = repository;
    }

    public Optional<ExternalService> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return repository.findByNameIgnoreCase(name.trim())
                .filter(ExternalService::isEnabled);
    }

    public Optional<ExternalService> findEnabledById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id).filter(ExternalService::isEnabled);
    }

    public List<ExternalService> listEnabled() {
        return repository.findAllByEnabledTrueOrderByNameAsc();
    }
}
