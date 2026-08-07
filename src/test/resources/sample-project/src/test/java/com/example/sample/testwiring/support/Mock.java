package com.example.sample.testwiring.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * NOT {@code org.mockito.Mock}. Exists to verify that {@code TestWiringIndex} self-reports
 * an unresolved declaration instead of assuming a field annotated {@code @Mock} is
 * necessarily the well-known Mockito annotation just because the simple name matches.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Mock {
}
