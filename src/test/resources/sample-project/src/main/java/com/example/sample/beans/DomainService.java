package com.example.sample.beans;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Custom stereotype: transitively a bean-making annotation via {@link UseCase}. */
@UseCase
@Retention(RetentionPolicy.RUNTIME)
public @interface DomainService {
}
