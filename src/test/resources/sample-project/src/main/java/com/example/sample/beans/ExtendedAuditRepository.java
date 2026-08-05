package com.example.sample.beans;

/**
 * Extends the project-local base, not a Spring Data interface directly — still a
 * framework-implemented repository, so still a terminal bean.
 */
public interface ExtendedAuditRepository extends BaseAuditRepository<Object, Long> {
}
