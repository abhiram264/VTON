package com.aifashionvault.platform.retention;

import java.util.List;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.retention.DeleteOutcome;
import com.aifashionvault.retention.RetentionCandidate;
import com.aifashionvault.retention.RetentionClass;
import com.aifashionvault.retention.RetentionInventory;
import com.aifashionvault.shared.StorageDomain;

/** Maps the closed retention vocabulary to TEMP object-store key families only. */
public final class PlatformRetentionInventory implements RetentionInventory {
    private final ObjectStore store;
    public PlatformRetentionInventory(ObjectStore store) {
        if (store == null) throw new IllegalArgumentException("store is required");
        this.store = store;
    }
    @Override
    public List<RetentionCandidate> list(RetentionClass retentionClass) {
        if (retentionClass == null) throw new IllegalArgumentException("retentionClass is required");
        return store.list(StorageDomain.TEMP, "").stream()
            .filter(object -> matches(retentionClass, object.key()))
            .map(object -> new RetentionCandidate(retentionClass, object.key(), object.lastModified()))
            .toList();
    }
    @Override
    public DeleteOutcome delete(RetentionCandidate candidate) {
        if (candidate == null) throw new IllegalArgumentException("candidate is required");
        try {
            store.delete(new ObjectRef(StorageDomain.TEMP, candidate.candidateId()));
            return DeleteOutcome.DELETED;
        } catch (ObjectStoreExceptions.StagedObjectMissingException ex) {
            return DeleteOutcome.ALREADY_ABSENT;
        }
    }
    static boolean matches(RetentionClass kind, String key) {
        if (key == null) return false;
        return switch (kind) {
            case TEMPORARY_GENERATION_INPUT -> key.contains("/generation-inputs/");
            case FAILED_INTERMEDIATE -> key.startsWith("temp/") && !key.contains("/uploads/");
            case IMPORT_STAGING -> key.startsWith("temp/") && key.contains("/uploads/");
        };
    }
}
