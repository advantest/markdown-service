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
 * Tests the validation rules checking the anchor identifiers declared in Markdown headings.
 * 
 * <p>Every expected issue is compared as a whole, message included, because the service has to
 * report exactly what the FluentMark Eclipse plug-ins report today. The marked text range always
 * starts at the number sign of the anchor declaration and ends behind the identifier, so the
 * closing brace stays unmarked.</p>
 */
public class MarkdownAnchorValidatorTest {

	private final MarkdownService service = new MarkdownService();

	@Test
	public void acceptsValidAndUniqueAnchorIdentifiers() {
		String markdown = """
				# Section X {#secX}

				## Section Y {#sec-Y_2:a.b}
				""";

		assertTrue(this.service.validateMarkdown(markdown).isEmpty(),
				"Valid and unique anchor identifiers must not be reported.");
	}

	@Test
	public void reportsAnchorIdentifierStartingWithADigit() {
		String markdown = "## Another One {#5sodjf}\n";

		int expectedStart = markdown.indexOf("#5sodjf");

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.ANCHOR_INVALID_IDENTIFIER, IssueSeverity.ERROR,
						invalidAnchorIdentifierMessage("5sodjf"),
						1, expectedStart, expectedStart + "#5sodjf".length())),
				this.service.validateMarkdown(markdown),
				"An anchor identifier has to start with a letter.");
	}

	@Test
	public void reportsAnchorIdentifierContainingBlanks() {
		String markdown = "## Another One {#s ss sd}\n";

		int expectedStart = markdown.indexOf("#s ss sd");

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.ANCHOR_INVALID_IDENTIFIER, IssueSeverity.ERROR,
						invalidAnchorIdentifierMessage("s ss sd"),
						1, expectedStart, expectedStart + "#s ss sd".length())),
				this.service.validateMarkdown(markdown),
				"An anchor identifier must not contain blanks.");
	}

	@Test
	public void reportsEmptyAnchorIdentifier() {
		String markdown = "## Another One {#}\n";

		int expectedStart = markdown.indexOf("#}");

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.ANCHOR_INVALID_IDENTIFIER, IssueSeverity.ERROR,
						invalidAnchorIdentifierMessage(""),
						1, expectedStart, expectedStart + "#".length())),
				this.service.validateMarkdown(markdown),
				"An anchor identifier has to contain at least one character.");
	}

	@Test
	public void reportsEveryDeclarationOfADuplicateAnchorIdentifier() {
		String markdown = """
				### Section Z {#secL}

				### Section Z {#secL}
				""";

		int firstStart = markdown.indexOf("#secL");
		int secondStart = markdown.indexOf("#secL", firstStart + 1);

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.ANCHOR_DUPLICATE_IDENTIFIER, IssueSeverity.ERROR,
								duplicateAnchorIdentifierMessage("secL", "1, 3"),
								1, firstStart, firstStart + "#secL".length()),
						new ValidationIssue(MarkdownIssueTypes.ANCHOR_DUPLICATE_IDENTIFIER, IssueSeverity.ERROR,
								duplicateAnchorIdentifierMessage("secL", "1, 3"),
								3, secondStart, secondStart + "#secL".length())),
				this.service.validateMarkdown(markdown),
				"Both declarations are reported, and both name the lines of all declarations.");
	}

	@Test
	public void reportsTheAnchorIdentifiersOfACorpusDocument() {
		// This is the content of a document of the recorded corpus. Company internal names
		// are replaced by generic ones of the same length, so the expected offsets are
		// still the recorded ones.
		// The line holding a single tabulator and the missing line end behind the last heading
		// are kept, because both would shift the offsets.
		String markdown = """
				# Section X {#secX}

				## Section Y {#secY}

				### Section Z {#secL}
				\t
				### Section Z {#secL}

				## Another One {#s ss sd}

				## Another One {#5sodjf}

				## Another One {#}\
				""";

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.ANCHOR_DUPLICATE_IDENTIFIER, IssueSeverity.ERROR,
								duplicateAnchorIdentifierMessage("secL", "5, 7"), 5, 58, 63),
						new ValidationIssue(MarkdownIssueTypes.ANCHOR_DUPLICATE_IDENTIFIER, IssueSeverity.ERROR,
								duplicateAnchorIdentifierMessage("secL", "5, 7"), 7, 82, 87),
						new ValidationIssue(MarkdownIssueTypes.ANCHOR_INVALID_IDENTIFIER, IssueSeverity.ERROR,
								invalidAnchorIdentifierMessage("s ss sd"), 9, 106, 114),
						new ValidationIssue(MarkdownIssueTypes.ANCHOR_INVALID_IDENTIFIER, IssueSeverity.ERROR,
								invalidAnchorIdentifierMessage("5sodjf"), 11, 133, 140),
						new ValidationIssue(MarkdownIssueTypes.ANCHOR_INVALID_IDENTIFIER, IssueSeverity.ERROR,
								invalidAnchorIdentifierMessage(""), 13, 159, 160)),
				this.service.validateMarkdown(markdown),
				"The service has to find the same anchor problems FluentMark recorded for this document.");
	}

	private static String invalidAnchorIdentifierMessage(String anchorIdentifier) {
		return "The anchor identifier \"" + anchorIdentifier + "\" is invalid."
				+ " It has to contain at least one character, must start with a letter,"
				+ " and is allowed to contain any number of the following characters in the remainder:"
				+ " letters ([A-Za-z]), digits ([0-9]), hyphens (\"-\"), underscores (\"_\"),"
				+ " colons (\":\"), and periods (\".\").";
	}

	private static String duplicateAnchorIdentifierMessage(String anchorIdentifier, String lines) {
		return "The anchor identifier \"" + anchorIdentifier + "\" is not unique."
				+ " The same identifier is used in the following lines: " + lines;
	}

}
