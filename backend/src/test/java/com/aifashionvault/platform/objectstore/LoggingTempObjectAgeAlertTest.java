package com.aifashionvault.platform.objectstore;

import java.time.Duration;
import com.aifashionvault.shared.StorageDomain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoggingTempObjectAgeAlertTest {

	private static final String KEY = "temp/user-1/job-9/object-a.jpg";
	private static final Duration AGE = Duration.ofHours(23);

	@Test
	void agedStoresKeyAndAgeOnly() {
		LoggingTempObjectAgeAlert alert = new LoggingTempObjectAgeAlert();
		ObjectRef object = new ObjectRef(StorageDomain.TEMP, KEY);

		alert.aged(object, AGE);

		LoggingTempObjectAgeAlert.LastAlert last = alert.lastAlert();
		assertThat(last.key()).isEqualTo(KEY);
		assertThat(last.ageHours()).isEqualTo(23L);
		assertThat(alert.lastMessage()).contains(KEY).contains("23");
		assertThat(last.toString()).contains(KEY).contains("23");
	}

	@Test
	void diagnosticPayloadOmitsUriBytesAndBase64EvenIfLogged() {
		LoggingTempObjectAgeAlert alert = new LoggingTempObjectAgeAlert();
		ObjectRef object = new ObjectRef(StorageDomain.TEMP, KEY);

		alert.aged(object, AGE);

		assertThat(alert.lastMessage())
			.contains(KEY)
			.doesNotContain("http://")
			.doesNotContain("https://")
			.doesNotContain("http")
			.doesNotContain("X-Amz-")
			.doesNotContain("Signature=")
			.doesNotContain("data:image")
			.doesNotContain("base64")
			.doesNotContain("/9j/")
			.doesNotContain("iVBORw0KGgo");
		assertThat(alert.lastAlert().toString())
			.doesNotContain("http://")
			.doesNotContain("https://")
			.doesNotContain("http")
			.doesNotContain("X-Amz-")
			.doesNotContain("Signature=")
			.doesNotContain("data:image")
			.doesNotContain("base64")
			.doesNotContain("/9j/")
			.doesNotContain("iVBORw0KGgo");
	}

	@Test
	void rejectsNullObjectAndAge() {
		LoggingTempObjectAgeAlert alert = new LoggingTempObjectAgeAlert();
		ObjectRef object = new ObjectRef(StorageDomain.TEMP, KEY);

		assertThatThrownBy(() -> alert.aged(null, AGE))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> alert.aged(object, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(alert.lastAlert()).isNull();
		assertThat(alert.lastMessage()).isNull();
	}
}
