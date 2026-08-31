package com.aifashionvault.retention;

import java.util.List;

public interface RetentionInventory {
    List<RetentionCandidate> list(RetentionClass retentionClass);
    DeleteOutcome delete(RetentionCandidate candidate);
}
