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
 * Tests that the validation does not depend on the line separators of the validated document.
 * 
 * <p>Markdown files use LF or CRLF, and a file may even mix both. A document has to be reported the
 * same way whichever it uses: the same issues, on the same lines, marking the same text. Only the
 * offsets differ, because a carriage return is a character of the document like any other and is
 * counted like any other.</p>
 */
public class MarkdownValidatorLineSeparatorTest {

	private static final String MESSAGE_EMPTY_TARGET = "The target file path or URL is empty.";

	/**
	 * Holds an issue of the link validator and one of the anchor validator, on different lines, so
	 * that the comparison covers both validators and more than one line separator preceding a
	 * finding.
	 */
	private static final String DOCUMENT = """
			# Overview {#overview}
			
			See the [details]() page.
			
			## Details {#overview}
			
			Read the [manual][missing] first.
			""";

	private final MarkdownService service = new MarkdownService();

	@Test
	public void reportsTheSameIssuesForCarriageReturnLineFeedAsForLineFeed() {
		List<ValidationIssue> issuesOfLineFeedDocument = this.service.validateMarkdown(DOCUMENT);

		assertTrue(issuesOfLineFeedDocument.size() > 2
				&& issuesOfLineFeedDocument.get(0).lineNumber() != issuesOfLineFeedDocument
						.get(issuesOfLineFeedDocument.size() - 1).lineNumber(),
				"The document has to produce several issues on different lines,"
						+ " otherwise the comparison below proves nothing.");

		List<ValidationIssue> expectedIssues = issuesOfLineFeedDocument.stream()
				.map(MarkdownValidatorLineSeparatorTest::shiftedByThePrecedingCarriageReturns)
				.toList();

		assertEquals(expectedIssues, this.service.validateMarkdown(withCarriageReturnLineFeed(DOCUMENT)),
				"The same document with CRLF has to be reported identically, except that every offset"
						+ " grows by the number of line separators preceding it.");
	}

	@Test
	public void reportsTheLineOfAnIssueInACarriageReturnLineFeedDocument() {
		String markdown = withCarriageReturnLineFeed("# Title\n\nSee the [overview]() for details.\n");

		int expectedStart = markdown.indexOf("()");

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_EMPTY_TARGET, IssueSeverity.ERROR,
						MESSAGE_EMPTY_TARGET, 3, expectedStart, expectedStart + "()".length())),
				this.service.validateMarkdown(markdown),
				"A carriage return is counted like any other character, and it does not open a line.");
	}

	@Test
	public void reportsTheLineOfAnIssueInADocumentMixingLineSeparators() {
		String markdown = "# Title\r\n\nSee the [overview]() for details.\r\n";

		int expectedStart = markdown.indexOf("()");

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_EMPTY_TARGET, IssueSeverity.ERROR,
						MESSAGE_EMPTY_TARGET, 3, expectedStart, expectedStart + "()".length())),
				this.service.validateMarkdown(markdown),
				"A document may mix line separators, e.g. after an edit on another operating system.");
	}

	private static String withCarriageReturnLineFeed(String markdownSourceCode) {
		return markdownSourceCode.replace("\n", "\r\n");
	}

	/**
	 * Answers the issue expected for the same document with CRLF. Each of the line separators
	 * preceding a finding grows by one character, and no finding of this test spans a line
	 * separator, so start and end offset move by the same amount.
	 */
	private static ValidationIssue shiftedByThePrecedingCarriageReturns(ValidationIssue issue) {
		int precedingCarriageReturns = issue.lineNumber() - 1;

		return new ValidationIssue(issue.issueTypeId(), issue.severity(), issue.message(), issue.lineNumber(),
				issue.startOffset() + precedingCarriageReturns, issue.endOffset() + precedingCarriageReturns);
	}

}
