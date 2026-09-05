package com.aifashionvault.platform.objectstore;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LoggingTempObjectAgeAlert implements TempObjectAgeAlert {

	private static final Logger log = LoggerFactory.getLogger(LoggingTempObjectAgeAlert.class);

	private LastAlert lastAlert;

	public record LastAlert(String key, long ageHours) {

		@Override
		public String toString() {
			return "temp object aged key=" + key + " ageHours=" + ageHours;
		}
	}

	@Override
	public void aged(ObjectRef object, Duration age) {
		if (object == null || age == null) {
			throw new IllegalArgumentException("object and age are required");
		}
		LastAlert event = new LastAlert(object.objectKey(), age.toHours());
		this.lastAlert = event;
		log.warn("temp object aged key={} ageHours={}", event.key(), event.ageHours());
	}

	public LastAlert lastAlert() {
		return lastAlert;
	}

	public String lastMessage() {
		return lastAlert == null ? null : lastAlert.toString();
	}
}
