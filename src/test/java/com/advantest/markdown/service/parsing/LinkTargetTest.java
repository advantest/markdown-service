/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests the split of a link target into scheme, path and fragment.
 */
public class LinkTargetTest {

	@Test
	public void readsAPlainFilePathAsAPath() {
		LinkTarget target = LinkTarget.of("documents/overview.md");

		assertNull(target.scheme(), "A file path has no scheme.");
		assertEquals("documents/overview.md", target.path());
		assertNull(target.fragment(), "A target without a hash tag has no fragment.");
		assertTrue(target.namesAFile());
	}

	@Test
	public void readsTheSchemeOfAWebAddress() {
		LinkTarget target = LinkTarget.of("https://example.com/page.html");

		assertEquals("https", target.scheme());
		assertFalse(target.namesAFile(), "Whoever owns the scheme resolves the target, not the file system.");
	}

	@Test
	public void separatesTheFragmentFromThePath() {
		LinkTarget target = LinkTarget.of("overview.md#the-section");

		assertEquals("overview.md", target.path());
		assertEquals("the-section", target.fragment(), "The fragment is read without its hash tag.");
		assertTrue(target.namesAFile());
	}

	@Test
	public void readsATargetOfNothingButAFragment() {
		LinkTarget target = LinkTarget.of("#the-section");

		assertEquals("the-section", target.fragment());
		assertFalse(target.namesAFile(), "A target pointing into the current document names no file.");
	}

	@Test
	public void splitsATargetTheUriSyntaxRejects() {
		LinkTarget target = LinkTarget.of("my documents/overview.md#the section");

		assertNull(target.scheme());
		assertEquals("my documents/overview.md", target.path());
		assertEquals("the section", target.fragment(),
				"A target split by hand loses its hash tag as well, unlike in FluentMark.");
	}

	@Test
	public void rejectsNull() {
		assertThrows(IllegalArgumentException.class, () -> LinkTarget.of(null));
	}

}
