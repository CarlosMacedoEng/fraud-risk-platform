package com.fraudplatform.decision.application;

/**
 * Domain events emitted by configuration and model governance. Written inside the same database
 * transaction as the change (outbox) by the messaging layer.
 */
public interface AdminEvents {

    void configurationChanged(String tenant, String environment, String action, String version, String previousVersion,
                              Integer rolloutPercentage, String actor, String reason);

    void modelStatusChanged(String tenant, String modelVersion, String fromStatus, String toStatus, String actor, String reason);
}
