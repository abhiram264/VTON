package com.aifashionvault.platform.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties(prefix = "vault.avatar")
public record AvatarApiProperties(boolean attestationRequired) {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AvatarApiProperties.class)
    static class Registrar {}
}
