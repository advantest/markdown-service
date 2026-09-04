/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.uri;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link UriReachability}, the answer to the question whether an address is there.
 */
class UriReachabilityTest {

	@Test
	void answerOfAnAddressCarriesItsStatusCode() {
		assertEquals(404, new UriReachability.Answered(404).statusCode());
	}

	@Test
	void addressThatWasNotReachedCarriesTheReason() {
		assertEquals("HTTP connect timed out",
				new UriReachability.NotReached("HTTP connect timed out").failureReason());
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "\t" })
	void addressThatWasNotReachedCannotBeSaidToHaveNoReason(String noReason) {
		assertThrows(IllegalArgumentException.class, () -> new UriReachability.NotReached(noReason));
	}

}
