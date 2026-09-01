/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.advantest.markdown.service.validation.IssueSeverity;
import com.advantest.markdown.service.validation.MarkdownIssueTypes;
import com.advantest.markdown.service.validation.ValidationIssue;

/**
 * Tests the validation rules checking Markdown link targets.
 * 
 * <p>Every expected issue is compared as a whole, message included, because the service has to
 * report exactly what the FluentMark Eclipse plug-ins report today. The expected offsets are
 * computed from the Markdown source code instead of being written down as numbers, so that a test
 * states which text range it expects to be marked.</p>
 */
public class MarkdownLinkValidationTest {

	private static final String MESSAGE_EMPTY_TARGET = "The target file path or URL is empty.";

	private final MarkdownService service = new MarkdownService();

	@Test
	public void reportsEmptyTargetOfInlineLink() {
		String markdown = "See the [overview]() for details.";

		int expectedStart = markdown.indexOf("()");

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_EMPTY_TARGET, IssueSeverity.ERROR,
						MESSAGE_EMPTY_TARGET, 1, expectedStart, expectedStart + "()".length())),
				this.service.validateMarkdown(markdown),
				"An empty link target has no character to mark, so the surrounding brackets are marked.");
	}

	@Test
	public void reportsEmptyTargetOfImage() {
		String markdown = "![A diagram]()";

		int expectedStart = markdown.indexOf("()");

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_EMPTY_TARGET, IssueSeverity.ERROR,
						MESSAGE_EMPTY_TARGET, 1, expectedStart, expectedStart + "()".length())),
				this.service.validateMarkdown(markdown),
				"Images are checked like links.");
	}

	@Test
	public void reportsBlankTargetOfInlineLink() {
		String markdown = "See the [overview](   ) for details.";

		int expectedStart = markdown.indexOf("(   ") + "(".length();

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_EMPTY_TARGET, IssueSeverity.ERROR,
						MESSAGE_EMPTY_TARGET, 1, expectedStart, expectedStart + "   ".length())),
				this.service.validateMarkdown(markdown),
				"A target of blanks has characters to mark, so the brackets stay unmarked.");
	}

	@Test
	public void reportsEmptyTargetOfLinkReferenceDefinition() {
		String markdown = "[overview]: \n";

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_EMPTY_TARGET, IssueSeverity.ERROR,
						MESSAGE_EMPTY_TARGET, 1, 0, "[overview]:".length())),
				this.service.validateMarkdown(markdown),
				"A link reference definition without a target is marked from its beginning.");
	}

	@Test
	public void reportsTheLineTheEmptyTargetIsIn() {
		String markdown = "# Title\n\nSee the [overview]() for details.\n";

		int expectedStart = markdown.indexOf("()");

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_EMPTY_TARGET, IssueSeverity.ERROR,
						MESSAGE_EMPTY_TARGET, 3, expectedStart, expectedStart + "()".length())),
				this.service.validateMarkdown(markdown),
				"Line numbers start at 1.");
	}

	@Test
	public void reportsEveryEmptyTargetOrderedByOffset() {
		String markdown = "See the [overview]() and the [details]() for details.\n";

		int firstStart = markdown.indexOf("()");
		int secondStart = markdown.indexOf("()", firstStart + 1);

		assertEquals(
				List.of(
						new ValidationIssue(MarkdownIssueTypes.LINK_EMPTY_TARGET, IssueSeverity.ERROR,
								MESSAGE_EMPTY_TARGET, 1, firstStart, firstStart + "()".length()),
						new ValidationIssue(MarkdownIssueTypes.LINK_EMPTY_TARGET, IssueSeverity.ERROR,
								MESSAGE_EMPTY_TARGET, 1, secondStart, secondStart + "()".length())),
				this.service.validateMarkdown(markdown));
	}

	@Test
	public void acceptsLinkWithTarget() {
		String markdown = "See the [overview](overview.md) for details.\n";

		assertTrue(this.service.validateMarkdown(markdown).isEmpty(),
				"A link with a target must not be reported.");
	}

	@Test
	public void acceptsLinkReferenceDefinitionWithTarget() {
		String markdown = "[overview]: https://example.com\n";

		assertTrue(this.service.validateMarkdown(markdown).isEmpty(),
				"A link reference definition with a target must not be reported.");
	}

}
