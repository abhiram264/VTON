package com.aifashionvault.platform.generation.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class BenchmarkReportJsonReleaseGateTest {

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	@Test
	void serializeProducesDeterministicKeysInExactOrder() throws Exception {
		GateFinding finding = new GateFinding(
				"fidelity-regression",
				"fidelity",
				"tops",
				"block",
				"0.9200",
				"0.8700",
				"fidelity regressed by 0.05 on category tops (threshold=0.02)");
		ReleaseGateDecision decision = new ReleaseGateDecision(
				ReleaseGateVerdict.BLOCK,
				"2026.01.0",
				42L,
				List.of(finding));

		String json = BenchmarkReportJson.serialize(decision);
		JsonNode root = MAPPER.readTree(json);

		assertThat(root.propertyNames()).containsExactly(
				"verdict",
				"benchmarkVersion",
				"matchedPairs",
				"findings");

		JsonNode findingsNode = root.get("findings");
		assertThat(findingsNode.isArray()).isTrue();
		assertThat(findingsNode).hasSize(1);

		JsonNode findingNode = findingsNode.get(0);
		assertThat(findingNode.propertyNames()).containsExactly(
				"rule",
				"dimension",
				"category",
				"severity",
				"baselineValue",
				"candidateValue",
				"detail");
	}

	@Test
	void serializeIsByteIdenticalAcrossRepeatedRuns() {
		GateFinding finding = new GateFinding(
				"p95-regression",
				"p95",
				"ALL",
				"block",
				"14200",
				"18500",
				"P95 latency increased by 30.28% (threshold=10.0%)");
		ReleaseGateDecision decision = new ReleaseGateDecision(
				ReleaseGateVerdict.BLOCK,
				"2026.01.0",
				100L,
				List.of(finding));

		String first = BenchmarkReportJson.serialize(decision);
		String second = BenchmarkReportJson.serialize(decision);

		assertThat(first).isEqualTo(second);
	}

	@Test
	void serializeRoundTripsAllScalarsWithExactValues() throws Exception {
		GateFinding finding = new GateFinding(
				"fail-rate-ceiling",
				"failRate",
				"ALL",
				"block",
				"0.0500",
				"0.1250",
				"candidate fail rate 0.1250 exceeds ceiling 0.1000");
		ReleaseGateDecision decision = new ReleaseGateDecision(
				ReleaseGateVerdict.BLOCK,
				"2026.01.0",
				75L,
				List.of(finding));

		String json = BenchmarkReportJson.serialize(decision);
		JsonNode root = MAPPER.readTree(json);

		assertThat(root.get("verdict").asText()).isEqualTo("BLOCK");
		assertThat(root.get("benchmarkVersion").asText()).isEqualTo("2026.01.0");
		assertThat(root.get("matchedPairs").asLong()).isEqualTo(75L);

		JsonNode findingNode = root.get("findings").get(0);
		assertThat(findingNode.get("rule").asText()).isEqualTo("fail-rate-ceiling");
		assertThat(findingNode.get("dimension").asText()).isEqualTo("failRate");
		assertThat(findingNode.get("category").asText()).isEqualTo("ALL");
		assertThat(findingNode.get("severity").asText()).isEqualTo("block");
		assertThat(findingNode.get("baselineValue").asText()).isEqualTo("0.0500");
		assertThat(findingNode.get("candidateValue").asText()).isEqualTo("0.1250");
		assertThat(findingNode.get("detail").asText()).isEqualTo("candidate fail rate 0.1250 exceeds ceiling 0.1000");
	}

	@Test
	void emptyFindingsSerializesWithEmptyArray() throws Exception {
		ReleaseGateDecision decision = new ReleaseGateDecision(
				ReleaseGateVerdict.PASS,
				"2026.01.0",
				50L,
				List.of());

		String json = BenchmarkReportJson.serialize(decision);
		JsonNode root = MAPPER.readTree(json);

		assertThat(root.get("verdict").asText()).isEqualTo("PASS");
		assertThat(root.get("benchmarkVersion").asText()).isEqualTo("2026.01.0");
		assertThat(root.get("matchedPairs").asLong()).isEqualTo(50L);
		assertThat(root.get("findings").isArray()).isTrue();
		assertThat(root.get("findings")).isEmpty();
		assertThat(json).contains("\"findings\":[]");
	}

	@Test
	void serializedOutputContainsNoForbiddenMarkersOrPayloads() {
		GateFinding f1 = new GateFinding(
				"pass-rate-floor",
				"qualityPassRate",
				"ALL",
				"review",
				"0.9500",
				"0.8200",
				"pass-rate regressed: [candidate=0.8200, baseline=0.9500, floor=0.9000, delta=-0.1300; eval_cases=50]");
		GateFinding f2 = new GateFinding(
				"missing-pairs",
				"coverage",
				"ALL",
				"review",
				"n/a",
				"n/a",
				"candidate missing 5 case pairs: id-001, id-002, id-003, id-004, id-005; sample_count=45 < baseline=50");
		ReleaseGateDecision decision = new ReleaseGateDecision(
				ReleaseGateVerdict.REVIEW,
				"2026.01.0",
				45L,
				List.of(f1, f2));

		String json = BenchmarkReportJson.serialize(decision);
		String lower = json.toLowerCase();

		// Media/URL/base64 markers.
		assertThat(lower).doesNotContain("http://").doesNotContain("https://").doesNotContain("base64")
				.doesNotContain("/9j/").doesNotContain("data:image");

		// Prompt/exception/provider body leakage markers.
		assertThat(lower).doesNotContain("deadbeef").doesNotContain("stack trace").doesNotContain("exception")
				.doesNotContain("prompt body");

		// Forbidden scoring fields: assert JSON keys / labels.
		assertThat(lower).doesNotContain("attractiveness").doesNotContain("beauty").doesNotContain("hotness")
				.doesNotContain("stylescore").doesNotContain("gender").doesNotContain("ethnicity");
		assertThat(json).doesNotContain("\"age\"");

		// No deployment automation / auto-promotion fields.
		assertThat(json).doesNotContain("\"approved\"").doesNotContain("\"deploymentdecision\"")
				.doesNotContain("\"autopromote\"").doesNotContain("\"deploy\"").doesNotContain("\"deploymentcommand\"");

		// No credentials or user ids.
		assertThat(lower).doesNotContain("password").doesNotContain("secret").doesNotContain("token")
				.doesNotContain("api-key").doesNotContain("credential");

		// Sane size limit for scalar-only decision JSON.
		assertThat(json.length()).isLessThan(4_096);
	}

	@Test
	void nullDecisionThrowsIllegalArgumentException() {
		assertThatThrownBy(() -> BenchmarkReportJson.serialize((ReleaseGateDecision) null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("decision must not be null");
	}
}
