package com.example.sample.beans;

import org.springframework.stereotype.Component;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Custom stereotype: a project-local annotation directly meta-annotated with {@code @Component}. */
@Component
@Retention(RetentionPolicy.RUNTIME)
public @interface UseCase {
}
