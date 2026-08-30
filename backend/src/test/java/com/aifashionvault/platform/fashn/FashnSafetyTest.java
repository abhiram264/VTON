package com.aifashionvault.platform.fashn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.aifashionvault.generation.vton.GenerationSubmission;
import com.aifashionvault.generation.vton.ProviderKey;
import com.aifashionvault.platform.environment.VaultEnvironmentProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class FashnSafetyTest {
    private static final String SECRET = "super-secret-fashn-key-value";

    @Test
    void fashnConfigurationToStringDoesNotExposeApiKey() {
        VaultEnvironmentProperties.Fashn fashn = new VaultEnvironmentProperties.Fashn(SECRET);
        assertThat(fashn.toString()).isEqualTo("Fashn[]");
        assertThat(fashn.toString()).doesNotContain(SECRET, "Bearer", "api");
        assertThat(new FashnMapper().toString()).doesNotContain(SECRET);
        assertThat(new FashnVtonProvider(new FashnMapper(), new FashnClient(FashnConfig.fashnRestClientBuilder("other-key").build()), new FashnTestSupport.FakeObjectStore()).toString())
            .doesNotContain(SECRET, "other-key", "Bearer");
    }

    @Test
    void providerFailuresAndExceptionsOmitUnsafePayloads() {
        FashnTestSupport.Harness harness = FashnTestSupport.harness();
        harness.server().expect(once(), requestTo(FashnTestSupport.RUN_URL))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(FashnTestSupport.apiError("BadRequest", "see https://evil.test/x?X-Amz-Signature=abc data:image/png;base64,abc")));

        GenerationSubmission submission = harness.provider().submit(FashnTestSupport.request(FashnTestSupport.STANDARD), FashnTestSupport.key());

        assertThat(submission).isInstanceOf(GenerationSubmission.Rejected.class);
        String text = ((GenerationSubmission.Rejected) submission).failure().toString() + submission;
        assertThat(text).doesNotContain("https://evil.test", "X-Amz-Signature", "data:image", "base64,abc", FashnTestSupport.API_KEY, "Bearer");
        harness.server().verify();
    }

    @Test
    void fashnTypesLiveOnlyInPlatformFashnPackage() throws IOException {
        Path javaRoot = locate("src", "main", "java");
        try (Stream<Path> walk = Files.walk(javaRoot)) {
            List<Path> fashnTypes = walk
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().startsWith("Fashn") && path.getFileName().toString().endsWith(".java"))
                .toList();
            assertThat(fashnTypes).isNotEmpty();
            assertThat(fashnTypes).allSatisfy(path ->
                assertThat(javaRoot.relativize(path).toString().replace('\\', '/'))
                    .startsWith("com/aifashionvault/platform/fashn/"));
        }
        Path generation = javaRoot.resolve("com/aifashionvault/generation");
        if (Files.isDirectory(generation)) {
            try (Stream<Path> walk = Files.walk(generation)) {
                assertThat(walk.filter(Files::isRegularFile).map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.startsWith("Fashn") && name.endsWith(".java"));
            }
        }
    }

    @Test
    void adapterDoesNotAddPersistenceOrWebhookSurface() throws IOException {
        Path root = locate("src", "main", "java").resolve("com/aifashionvault/platform/fashn");
        try (Stream<Path> walk = Files.walk(root)) {
            String source = walk
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .map(FashnSafetyTest::read)
                .reduce("", (left, right) -> left + "\n" + right);
            assertThat(source)
                .doesNotContain("Flyway", "GenerationJob", "GenerationAttempt", "GenerationOutputStore", "Redis", "webhook")
                .doesNotContain("orgId", "userId");
        }
        assertThat(new FashnVtonProvider(new FashnMapper(), new FashnClient(FashnConfig.fashnRestClientBuilder("k").build()), new FashnTestSupport.FakeObjectStore()).providerKey())
            .isEqualTo(new ProviderKey("fashn"));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(path.toString());
        }
    }

    private static Path locate(String first, String... more) {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path current = cwd;
        while (current != null) {
            Path direct = current.resolve(Path.of(first, more));
            if (Files.isDirectory(direct)) {
                return direct;
            }
            Path underBackend = current.resolve("backend").resolve(Path.of(first, more));
            if (Files.isDirectory(underBackend)) {
                return underBackend;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate " + Path.of(first, more) + " from " + cwd);
    }
}
