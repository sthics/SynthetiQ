package dev.synthetiq.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "synthetiq.github")
public record GitHubProperties(long appId, String privateKeyPath, String webhookSecret) {}
