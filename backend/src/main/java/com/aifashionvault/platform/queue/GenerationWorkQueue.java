package com.aifashionvault.platform.queue;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface GenerationWorkQueue {

	void send(UUID generationJobId);

	Optional<GenerationWorkLease> receive();

	void extendVisibility(GenerationWorkLease lease, Duration extension);

	void delete(GenerationWorkLease lease);
}
