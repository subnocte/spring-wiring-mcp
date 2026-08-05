package com.example.sample.beans;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exercises method-level {@code @Transactional} status, same-class self-invocation that
 * bypasses the proxy ({@code notifyAll2 -> audit}), and a dead {@code @Transactional} on
 * a private method ({@code secret}).
 */
@Service
public class OrderTxService {

    @Transactional
    public void place() {
        validate();
        audit();
    }

    public void validate() {
    }

    @Transactional
    public void audit() {
    }

    public void notifyAll2() {
        audit();
    }

    @Transactional
    private void secret() {
    }
}
