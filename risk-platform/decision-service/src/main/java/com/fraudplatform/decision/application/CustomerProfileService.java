package com.fraudplatform.decision.application;

import com.fraudplatform.decision.config.PlatformProperties;
import com.fraudplatform.decision.domain.CustomerProfile;
import com.fraudplatform.decision.integration.CustomerProfileClient;
import com.fraudplatform.decision.persistence.CustomerRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Profile lookup order: local replica (PostgreSQL, fed by batch files and events) → customer's profile
 * API for customers not yet replicated → conservative default. The replica keeps the hot path
 * independent of the customer's CRM availability.
 */
@Service
public class CustomerProfileService {

    private final CustomerRepository repository;
    private final Optional<CustomerProfileClient> remote;
    private final PlatformProperties props;

    public CustomerProfileService(CustomerRepository repository, Optional<CustomerProfileClient> remote, PlatformProperties props) {
        this.repository = repository;
        this.remote = remote;
        this.props = props;
    }

    public record Result(CustomerProfile profile, boolean degraded) {
    }

    public Result find(String tenantId, String customerId) {
        Optional<CustomerProfile> local = repository.find(tenantId, customerId);
        if (local.isPresent()) return new Result(local.get(), false);
        if (remote.isPresent()) {
            Optional<CustomerProfile> fetched = remote.get().fetch(tenantId, customerId);
            if (fetched.isPresent()) {
                repository.upsert(tenantId, fetched.get(), "API");
                return new Result(fetched.get(), false);
            }
        }
        return new Result(CustomerProfile.unknown(customerId, props.tenant(tenantId).defaultHomeCountry()), true);
    }
}
