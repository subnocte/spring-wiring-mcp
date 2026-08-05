package com.example.sample.beans;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Class-level {@code @Transactional} covers public methods; a private method stays non-transactional. */
@Service
@Transactional
public class TxClassService {

    public void save() {
    }

    public void load() {
    }

    private void helper() {
    }
}
