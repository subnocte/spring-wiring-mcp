package com.example.sample.beans;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Mutual dependency cycle with {@link CycleAService}, to exercise traversal safety. */
@Service
public class CycleBService {

    @Autowired
    private CycleAService cycleA;
}
