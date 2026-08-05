package com.example.sample.beans;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository: a terminal bean, injectable but with no outgoing edges. */
public interface OrderSearchRepository extends JpaRepository<Object, Long> {
}
