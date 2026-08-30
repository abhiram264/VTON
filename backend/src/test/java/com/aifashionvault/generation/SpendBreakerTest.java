package com.aifashionvault.generation;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.platform.generation.ConfiguredSpendBreaker;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Deterministic hard cost breaker behavior (Q55): ceilings are reached-or-
 * exceeded trip points, spend accumulates per user and globally, and zero,
 * negative, or null amounts never corrupt accounting. No wall clock is
 * involved; blocking stays blocking until spend accounting says otherwise.
 */
class SpendBreakerTest {

	private static final UserId USER = new UserId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
	private static final UserId OTHER_USER = new UserId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
	private static final BigDecimal GLOBAL_CEILING = new BigDecimal("100.00");
	private static final BigDecimal PER_USER_CEILING = new BigDecimal("10.00");

	@Test
	void freshBreakerAllowsNewExecutions() {
		ConfiguredSpendBreaker breaker = breaker();

		assertThat(breaker.blocksNewProviderExecution(USER)).isFalse();
		assertThat(breaker.blocksNewProviderExecution(OTHER_USER)).isFalse();
	}

	@Test
	void userSpendBelowCeilingDoesNotBlock() {
		ConfiguredSpendBreaker breaker = breaker();
		breaker.recordAttemptSpend(USER, new BigDecimal("9.99"));

		assertThat(breaker.blocksNewProviderExecution(USER)).isFalse();
	}

	@Test
	void userSpendAtCeilingBlocks() {
		ConfiguredSpendBreaker breaker = breaker();
		breaker.recordAttemptSpend(USER, PER_USER_CEILING);

		assertThat(breaker.blocksNewProviderExecution(USER)).isTrue();
	}

	@Test
	void userSpendAboveCeilingBlocks() {
		ConfiguredSpendBreaker breaker = breaker();
		breaker.recordAttemptSpend(USER, new BigDecimal("10.01"));

		assertThat(breaker.blocksNewProviderExecution(USER)).isTrue();
	}

	@Test
	void spendAccumulatesAcrossMultipleAttempts() {
		ConfiguredSpendBreaker breaker = breaker();
		breaker.recordAttemptSpend(USER, new BigDecimal("4.00"));
		breaker.recordAttemptSpend(USER, new BigDecimal("4.00"));

		assertThat(breaker.blocksNewProviderExecution(USER)).isFalse();

		breaker.recordAttemptSpend(USER, new BigDecimal("4.00"));

		assertThat(breaker.blocksNewProviderExecution(USER)).isTrue();
	}

	@Test
	void perUserCeilingIsIndependentPerUser() {
		ConfiguredSpendBreaker breaker = breaker();
		breaker.recordAttemptSpend(USER, PER_USER_CEILING);

		assertThat(breaker.blocksNewProviderExecution(USER)).isTrue();
		assertThat(breaker.blocksNewProviderExecution(OTHER_USER)).isFalse();
	}

	@Test
	void globalCeilingTripsForEveryUser() {
		ConfiguredSpendBreaker breaker = breaker();
		breaker.recordAttemptSpend(USER, GLOBAL_CEILING.subtract(new BigDecimal("1.00")));

		assertThat(breaker.blocksNewProviderExecution(OTHER_USER)).isFalse();

		breaker.recordAttemptSpend(OTHER_USER, new BigDecimal("1.00"));

		assertThat(breaker.blocksNewProviderExecution(USER)).isTrue();
		assertThat(breaker.blocksNewProviderExecution(OTHER_USER)).isTrue();
	}

	@Test
	void zeroAmountIsANoOp() {
		ConfiguredSpendBreaker breaker = breaker();
		breaker.recordAttemptSpend(USER, BigDecimal.ZERO);
		breaker.recordAttemptSpend(USER, new BigDecimal("0.00"));

		assertThat(breaker.blocksNewProviderExecution(USER)).isFalse();

		breaker.recordAttemptSpend(USER, new BigDecimal("10.00"));
		assertThat(breaker.blocksNewProviderExecution(USER)).isTrue();
	}

	@Test
	void negativeAmountIsRejected() {
		ConfiguredSpendBreaker breaker = breaker();

		assertThatIllegalArgumentException()
			.isThrownBy(() -> breaker.recordAttemptSpend(USER, new BigDecimal("-0.01")));

		assertThat(breaker.blocksNewProviderExecution(USER)).isFalse();
	}

	@Test
	void nullAmountIsRejected() {
		ConfiguredSpendBreaker breaker = breaker();

		assertThatIllegalArgumentException()
			.isThrownBy(() -> breaker.recordAttemptSpend(USER, null));

		assertThat(breaker.blocksNewProviderExecution(USER)).isFalse();
	}

	@Test
	void nullUserIdIsRejectedOnBothOperations() {
		ConfiguredSpendBreaker breaker = breaker();

		assertThatIllegalArgumentException()
			.isThrownBy(() -> breaker.blocksNewProviderExecution(null));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> breaker.recordAttemptSpend(null, new BigDecimal("1.00")));
	}

	@Test
	void constructorRequiresPositiveCeilings() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ConfiguredSpendBreaker(null, PER_USER_CEILING));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ConfiguredSpendBreaker(GLOBAL_CEILING, null));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ConfiguredSpendBreaker(BigDecimal.ZERO, PER_USER_CEILING));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ConfiguredSpendBreaker(GLOBAL_CEILING, new BigDecimal("-1.00")));
	}

	private static ConfiguredSpendBreaker breaker() {
		return new ConfiguredSpendBreaker(GLOBAL_CEILING, PER_USER_CEILING);
	}
}
