package com.example.sample.beans;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Project-local repository base: real codebases commonly insert one of these between
 * their repositories and Spring Data. Terminal-bean detection must follow the chain.
 */
public interface BaseAuditRepository<T, ID> extends JpaRepository<T, ID> {
}
