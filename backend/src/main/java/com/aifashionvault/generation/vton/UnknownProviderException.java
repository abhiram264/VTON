package com.aifashionvault.generation.vton;

public final class UnknownProviderException extends IllegalArgumentException {
    public UnknownProviderException(ProviderKey providerKey) { super("unknown VTON provider: " + providerKey.value()); }
}
