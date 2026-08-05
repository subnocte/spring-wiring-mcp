package com.example.sample.beans;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("local")
public class InMemoryCacheProvider implements CacheProvider {
}
