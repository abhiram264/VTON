package com.aifashionvault.platform.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Guards the dead-configuration defect found while assessing the conditional
 * "expose the work-queue timing knobs as properties" (t282).
 *
 * <p>{@code application.yaml} used to declare seven {@code vault.generation.queue.*}
 * keys (visibility timeout, heartbeat, long poll, batch size, main/DLQ retention,
 * max receive count) that NO Java code ever read: changing
 * {@code visibility-timeout-seconds} from 120 to 600 produced no behavioural
 * change whatsoever, while the values that actually govern the queue are the
 * constants in {@code SqsGenerationWorkQueue} / {@code GenerationWorkProcessor}
 * and the queue provisioning in {@code infra/}.
 *
 * <p>Resizing the queue is not a runtime knob, so the honest fix was to delete the
 * misleading block rather than bind it. This test keeps it deleted: any key
 * declared under {@code queue:} here must be either removed or genuinely bound
 * and read in {@code src/main/java}, never left as a silent no-op.
 */
class GenerationQueueConfigDeclarationTest {

	@Test
	void applicationYamlDeclaresNoUnreadQueueKnobs() throws IOException {
		Path yaml = locate("src", "main", "resources", "application.yaml");
		assertThat(Files.isRegularFile(yaml)).as("application.yaml must exist").isTrue();
		List<String> declared = declaredQueueKeys(Files.readString(yaml, StandardCharsets.UTF_8));

		assertThat(declared)
			.as("vault.generation.queue.* must declare no key that no code reads — queue sizing "
				+ "lives in the SqsGenerationWorkQueue/GenerationWorkProcessor constants and the queue "
				+ "provisioning, so re-declaring it here creates a knob that silently does nothing")
			.isEmpty();
	}

	/** Key names indented under a {@code queue:} line anywhere in the yaml. */
	private static List<String> declaredQueueKeys(String yaml) {
		Pattern key = Pattern.compile("^(\\s+)([a-z0-9-]+):\\s*\\S.*$");
		List<String> declared = new ArrayList<>();
		int queueIndent = -1;
		for (String line : yaml.split("\\R", -1)) {
			if (line.trim().equals("queue:")) {
				queueIndent = line.indexOf('q');
				continue;
			}
			Matcher matcher = key.matcher(line);
			if (queueIndent >= 0 && matcher.matches() && matcher.group(1).length() > queueIndent) {
				declared.add(matcher.group(2));
			}
		}
		return declared;
	}

	/** Walks up from the working directory so the test runs from module or repo root. */
	private static Path locate(String first, String... more) {
		Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		while (current != null) {
			Path direct = current.resolve(Path.of(first, more));
			if (Files.exists(direct)) {
				return direct;
			}
			Path underBackend = current.resolve("backend").resolve(Path.of(first, more));
			if (Files.exists(underBackend)) {
				return underBackend;
			}
			current = current.getParent();
		}
		throw new IllegalStateException("Cannot locate " + Path.of(first, more));
	}
}
