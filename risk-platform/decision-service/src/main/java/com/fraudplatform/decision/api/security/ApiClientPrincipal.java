package com.fraudplatform.decision.api.security;

import java.util.Set;

/** Authenticated API client. The tenant is derived from the credential, never from the request body. */
public record ApiClientPrincipal(String clientId, String tenantId, Set<String> roles) {
}
