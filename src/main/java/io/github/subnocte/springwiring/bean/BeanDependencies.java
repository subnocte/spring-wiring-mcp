package io.github.subnocte.springwiring.bean;

import java.util.List;

/**
 * A bean and its analyzed dependency sites, in field declaration order.
 */
public record BeanDependencies(
        BeanDefinition bean,
        List<BeanEdge> edges
) {
}
