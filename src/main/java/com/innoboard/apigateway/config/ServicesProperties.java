package com.innoboard.apigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.services")
public record ServicesProperties(String authUrl, String userUrl) {
}
