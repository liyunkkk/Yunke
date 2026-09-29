package io.github.mangi.eta.agent.delegation

/** User-assigned responsibility, not a benchmark or an inferred model capability. */
internal enum class SubAgentTaskTier(val wireValue: String, val label: String, val routingHint: String, val scope: SubAgentScope) {
    SIMPLE("simple", "简单任务", "search, organization, mechanical changes with explicit instructions", SubAgentScope.QUICK),
    REGULAR("regular", "常规任务", "localized fixes and well-scoped feature implementation", SubAgentScope.COMPARE),
    COMPLEX("complex", "复杂任务", "cross-module changes, architecture, difficult debugging and edge cases", SubAgentScope.DEEP);

    companion object {
        fun fromWireValue(value: String): SubAgentTaskTier? = entries.firstOrNull { it.wireValue == value }

        /** 档位到预算档位的映射；未设置档位（如研究/审查代理）按中间档处理。 */
        fun scopeOf(tier: SubAgentTaskTier?): SubAgentScope = tier?.scope ?: SubAgentScope.COMPARE
    }
}
