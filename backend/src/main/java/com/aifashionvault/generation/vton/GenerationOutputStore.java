package com.aifashionvault.generation.vton;

import java.io.IOException;

public interface GenerationOutputStore {
    PrivateMediaRef writePrivateTemp(ProviderMediaSource source) throws IOException;

    /**
     * The private object store could not durably hold the candidate output
     * even after bounded local recovery (Q33/T18). This is terminal for the
     * attempt: callers must not repeat provider execution to replace an
     * output that simply could not be stored; the engine terminalizes it as
     * STORAGE_UNAVAILABLE. Distinct from generic {@link IOException} so the
     * caller can terminalize without inspecting platform error vocabulary.
     */
    public final class StorageUnavailableException extends IOException {
        public StorageUnavailableException(String message) {
            super(message);
        }

        public StorageUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
