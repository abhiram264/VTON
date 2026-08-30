package com.aifashionvault.platform.web;

import java.net.URI;
import java.time.Instant;

public record ReadCapability(URI url, Instant expiresAt) {}
