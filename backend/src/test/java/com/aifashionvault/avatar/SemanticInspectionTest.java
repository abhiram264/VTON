package com.aifashionvault.avatar;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticInspectionTest {

	@Test
	void exposesOnlyTheDecisionFields() {
		SemanticInspection inspection = new SemanticInspection(
			PersonCountClass.ONE,
			true,
			false,
			AvatarPreflightPolicy.VERSION);

		assertThat(inspection.personCountClass()).isEqualTo(PersonCountClass.ONE);
		assertThat(inspection.faceVisible()).isTrue();
		assertThat(inspection.bodyVisible()).isFalse();
		assertThat(inspection.evaluatorVersion()).isEqualTo("avatar-preflight-v1");
		assertThat(Arrays.stream(SemanticInspection.class.getRecordComponents())
			.map(component -> component.getName()))
			.containsExactly("personCountClass", "faceVisible", "bodyVisible", "evaluatorVersion");
		assertThat(inspection.toString()).isEqualTo(
			"SemanticInspection[personCountClass=ONE, faceVisible=true, bodyVisible=false, "
				+ "evaluatorVersion=avatar-preflight-v1]");
	}

	@Test
	void rejectsMissingOrBlankRequiredFields() {
		assertThatThrownBy(() -> new SemanticInspection(null, true, true, "evaluator-v1"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("personCountClass is required");

		for (String evaluatorVersion : new String[] {null, "", " ", "\t\n"}) {
			assertThatThrownBy(() -> new SemanticInspection(
				PersonCountClass.ONE,
				true,
				true,
				evaluatorVersion))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("evaluatorVersion is required");
		}
	}

	@Test
	void freezesPersonCountClasses() {
		assertThat(PersonCountClass.values())
			.containsExactly(PersonCountClass.ZERO, PersonCountClass.ONE, PersonCountClass.MULTIPLE);
	}

	@Test
	void semanticInspectorIsAnImageBytesDecisionPort() throws Exception {
		SemanticInspection expected = new SemanticInspection(
			PersonCountClass.ONE,
			true,
			true,
			"evaluator-v1");
		SemanticInspector inspector = imageBytes -> expected;

		assertThat(inspector.inspect(new byte[] {1, 2, 3})).isSameAs(expected);
		assertThat(SemanticInspector.class.getDeclaredMethods()).singleElement().satisfies(method -> {
			assertThat(method.getName()).isEqualTo("inspect");
			assertThat(method.getParameterTypes()).containsExactly(byte[].class);
			assertThat(method.getReturnType()).isEqualTo(SemanticInspection.class);
			assertThat(method.getExceptionTypes())
				.containsExactly(SemanticInspectionUnavailableException.class);
		});
	}

	@Test
	void unavailableSignalHasOnlyTheStableMessage() {
		SemanticInspectionUnavailableException unavailable =
			new SemanticInspectionUnavailableException();

		assertThat(unavailable.getMessage()).isEqualTo("unavailable");
		assertThat(unavailable.getCause()).isNull();
		assertThat(unavailable.toString())
			.contains("unavailable")
			.doesNotContain("path", "media", "http", "byte[");
	}

	@Test
	void freezesOpaquePolicyConstants() throws Exception {
		assertThat(AvatarPreflightPolicy.MIN_SHORT_SIDE).isEqualTo(512);
		assertThat(AvatarPreflightPolicy.VERSION).isEqualTo("avatar-preflight-v1");
		assertThat(AvatarPreflightPolicy.class.getDeclaredFields()).allSatisfy(field -> {
			int modifiers = field.getModifiers();
			assertThat(Modifier.isPublic(modifiers)).isTrue();
			assertThat(Modifier.isStatic(modifiers)).isTrue();
			assertThat(Modifier.isFinal(modifiers)).isTrue();
		});
		assertThat(AvatarPreflightPolicy.class.getDeclaredConstructor().canAccess(null)).isFalse();
	}
}
