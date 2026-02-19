package dev.synthetiq.config;

import dev.synthetiq.domain.enums.AiTier;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "synthetiq.agents")
public record AgentProperties(AgentConfig security, AgentConfig performance,
                                AgentConfig architecture, AgentConfig refactoring) {
    public record AgentConfig(boolean enabled, AiTier maxTier) {
        public AgentConfig { if (maxTier == null) maxTier = AiTier.LOCAL; }
    }
}
