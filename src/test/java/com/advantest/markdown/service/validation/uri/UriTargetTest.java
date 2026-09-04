/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.uri;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link UriTarget}, the target handed to a {@link UriValidator}.
 */
class UriTargetTest {

	@Test
	void targetIsReadAsAUriWhereTheSyntaxAllowsIt() {
		UriTarget target = UriTarget.of("https://example.org/guide", 3, 17, 42);

		assertEquals(Optional.of(URI.create("https://example.org/guide")), target.uri());
		assertEquals("https", target.scheme().orElseThrow());
	}

	@Test
	void targetKeepsItsTextEvenWhereTheSyntaxRejectsIt() {
		UriTarget target = UriTarget.of("https://example.org/a guide", 1, 0, 27);

		assertEquals("https://example.org/a guide", target.text());
		assertTrue(target.uri().isEmpty());
		assertTrue(target.scheme().isEmpty());
	}

	@Test
	void targetKeepsWhatFollowsTheAddress() {
		UriTarget target = UriTarget.of("https://example.org/guide?page=2#section", 1, 0, 40);

		assertEquals("https://example.org/guide?page=2#section", target.text());
		assertEquals("section", target.uri().orElseThrow().getFragment());
		assertEquals("page=2", target.uri().orElseThrow().getQuery());
	}

	@Test
	void targetWithoutASchemeNamesNone() {
		UriTarget target = UriTarget.of("guide/introduction.md", 1, 0, 21);

		assertTrue(target.scheme().isEmpty());
	}

	@Test
	void targetKnowsWhereItStands() {
		UriTarget target = UriTarget.of("mailto:someone@example.org", 7, 120, 146);

		assertEquals(7, target.lineNumber());
		assertEquals(120, target.startOffset());
		assertEquals(146, target.endOffset());
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "\t" })
	void targetWithoutATextIsNoTarget(String noText) {
		assertThrows(IllegalArgumentException.class, () -> UriTarget.of(noText, 1, 0, 0));
	}

	@Test
	void targetEitherIsAUriOrIsNoneButNeverNull() {
		assertThrows(IllegalArgumentException.class,
				() -> new UriTarget("https://example.org", null, 1, 0, 19));
	}

	@Test
	void targetStandsSomewhereInTheDocument() {
		assertThrows(IllegalArgumentException.class,
				() -> UriTarget.of("https://example.org", 0, 0, 19));
		assertThrows(IllegalArgumentException.class,
				() -> UriTarget.of("https://example.org", 1, -1, 19));
		assertThrows(IllegalArgumentException.class,
				() -> UriTarget.of("https://example.org", 1, 19, 0));
	}

}
