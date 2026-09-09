/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.advantest.markdown.service.validation.IssueSeverity;
import com.advantest.markdown.service.validation.MarkdownIssueTypes;
import com.advantest.markdown.service.validation.ValidationIssue;

/**
 * Tests the validation rules checking Markdown link targets.
 * 
 * <p>Every expected issue is compared as a whole, message included, because a message is what a
 * reader gets to see. The expected offsets are
 * computed from the Markdown source code instead of being written down as numbers, so that a test
 * states which text range it expects to be marked.</p>
 */
public class MarkdownLinkValidatorTest {

	private static final String MESSAGE_EMPTY_TARGET = "The target file path or URL is empty.";

	private static final String EMPTY_REFERENCE_LINK_LABEL_MESSAGE =
			"The reference link label is empty. Please create a link reference definition like"
			+ " \"[ReferenceLinkLabel]: https://plantuml.com\""
			+ " and use that reference link label in your link,"
			+ " e.g. \"[your link text][ReferenceLinkLabel]\" or \"[ReferenceLinkLabel]\".";

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
		String markdown = "See the [overview](https://example.com/overview.md) for details.\n";

		assertTrue(this.service.validateMarkdown(markdown).isEmpty(),
				"A link with a target must not be reported.");
	}

	@Test
	public void acceptsLinkReferenceDefinitionWithTarget() {
		String markdown = "[overview]: https://example.com\n";

		assertTrue(this.service.validateMarkdown(markdown).isEmpty(),
				"A link reference definition with a target must not be reported.");
	}

	/**
	 * A target may be anything an author writes between the brackets, and a validator has to cope
	 * with all of it. A target that no rule has anything to say about is accepted, and none of them
	 * may make the validation fail.
	 */
	@ParameterizedTest
	@ValueSource(strings = {
			"https://example.com",
			"https://example.com/search?test=true&value=4",
			"https://example.com/search?test=true&value=4#results",
			"https://example.com:8443/a/b/c?query=a+b",
			"http://example.com/a%20path/with%20blanks.html",
			"ftp://example.com/archive.zip",
			"mailto:someone@example.com",
			"mailto:someone@example.com?subject=Hello",
			"file:///C:/documents/overview.md",
			"urn:isbn:0451450523",
			"#a-section-of-this-document",
			"#doSomething(int,boolean)" })
	public void acceptsATargetNoRuleHasAnythingToSayAbout(String linkTarget) {
		String markdown = "# A section of this document {#a-section-of-this-document}\n\n"
				+ "See the [something](" + linkTarget + ") for details.\n";

		assertTrue(this.service.validateMarkdown(markdown).isEmpty(),
				"Nothing is wrong with this target: " + linkTarget);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"overview.md",
			"path/with/slash/",
			"../../overview.md",
			"./../../some/path/to/../../other/dir/file.txt",
			"../src/com/example/project/SomeClass.java#isCool",
			"../../../simple-java-project/src/com/example/project/x/SomeClass.java"
					+ "#doSomething(int,boolean,Character[],List<Map<K,V>>)",
			"a document with blanks.md",
			"documents/overview.md#a-section",
			"C:\\documents\\overview.md",
			"\\\\server\\share\\overview.md",
			"?query=only",
			"](unbalanced brackets" })
	public void copesWithAnyTargetAnAuthorWrites(String linkTarget) {
		String markdown = "See the [something](" + linkTarget + ") for details.\n";

		assertDoesNotThrow(() -> this.service.validateMarkdown(markdown),
				"A target the validation cannot resolve is reported, never thrown about: " + linkTarget);
	}

	@Test
	public void reportsFullReferenceLinkWithoutDefinition() {
		String markdown = "See [the overview][overview] for details.";

		int expectedStart = markdown.indexOf("[overview]") + "[".length();

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_MISSING_REFERENCE_DEFINITION,
						IssueSeverity.ERROR, missingReferenceDefinitionMessage("overview"),
						1, expectedStart, expectedStart + "overview".length())),
				this.service.validateMarkdown(markdown),
				"The label of a full reference link is marked, not the whole link.");
	}

	@Test
	public void reportsShortcutReferenceLinkWithoutDefinition() {
		String markdown = "See [overview] for details.";

		int expectedStart = markdown.indexOf("overview");

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_MISSING_REFERENCE_DEFINITION,
						IssueSeverity.ERROR, missingReferenceDefinitionMessage("overview"),
						1, expectedStart, expectedStart + "overview".length())),
				this.service.validateMarkdown(markdown));
	}

	@Test
	public void acceptsFootnoteReferencesWithTheirDefinitions() {
		// This is the content of a document of the recorded corpus. Company internal names
		// are replaced by generic ones of the same length, so the expected offsets are
		// still the recorded ones.
		String markdown = """
				Some text with footnotes here[^1] and there[^2].
				Other references work either[^other].

				[^1]: footnote 1
				[^2]:footnote 2
				[^other]:
				    Other footnote.
				""";

		assertTrue(this.service.validateMarkdown(markdown).isEmpty(),
				"A footnote reference is a footnote reference, not a reference link without a definition.");
	}

	@Test
	public void reportsCollapsedReferenceLinkWithoutDefinition() {
		String markdown = "See [the overview][] for details.";

		int expectedStart = markdown.indexOf("[the overview][]");

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_AMBIGUOUS_REFERENCE,
						IssueSeverity.ERROR, ambiguousReferenceMessage("the overview"),
						1, expectedStart, expectedStart + "[the overview][]".length())),
				this.service.validateMarkdown(markdown),
				"A collapsed reference link cannot be told apart from a full one without a label,"
						+ " so the whole link is marked and the message names both possibilities.");
	}

	@Test
	public void reportsEmptyReferenceLinkLabel() {
		String markdown = "See [][] for details.";

		int expectedStart = markdown.indexOf("[]", markdown.indexOf("[]") + 1);

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_EMPTY_REFERENCE_LABEL,
						IssueSeverity.ERROR, EMPTY_REFERENCE_LINK_LABEL_MESSAGE,
						1, expectedStart, expectedStart + "[]".length())),
				this.service.validateMarkdown(markdown),
				"An empty label has no character to mark, so the surrounding brackets are marked.");
	}

	@Test
	public void reportsBlankReferenceLinkLabel() {
		String markdown = "See [the overview][ ] for details.";

		int expectedStart = markdown.indexOf("[ ]") + "[".length();

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_EMPTY_REFERENCE_LABEL,
						IssueSeverity.ERROR, EMPTY_REFERENCE_LINK_LABEL_MESSAGE,
						1, expectedStart, expectedStart + " ".length())),
				this.service.validateMarkdown(markdown),
				"A label of blanks has a character to mark, so the brackets stay unmarked.");
	}

	@Test
	public void reportsEmptyReferenceLinkLabelOfLinkWithBlankText() {
		String markdown = "See [ ][] for details.";

		int expectedStart = markdown.indexOf("[]");

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_EMPTY_REFERENCE_LABEL,
						IssueSeverity.ERROR, EMPTY_REFERENCE_LINK_LABEL_MESSAGE,
						1, expectedStart, expectedStart + "[]".length())),
				this.service.validateMarkdown(markdown),
				"Blank link text does not turn this into a collapsed reference link,"
						+ " so the empty label is reported rather than the missing definition.");
	}

	@Test
	public void acceptsFullReferenceLinkWithDefinition() {
		String markdown = "See [the overview][overview] for details.\n\n[overview]: https://example.com\n";

		assertTrue(this.service.validateMarkdown(markdown).isEmpty(),
				"A reference link with a definition must not be reported.");
	}

	@Test
	public void acceptsShortcutReferenceLinkWithDefinition() {
		String markdown = "See [overview] for details.\n\n[overview]: https://example.com\n";

		assertTrue(this.service.validateMarkdown(markdown).isEmpty(),
				"A shortcut reference link with a definition must not be reported.");
	}

	@Test
	public void reportsTaskListItemsAsReferenceLinks() {
		String markdown = "* [ ] unchecked\n* [x] checked item\n";

		int uncheckedStart = markdown.indexOf("[ ]") + "[".length();
		int checkedStart = markdown.indexOf("[x]") + "[".length();

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_EMPTY_REFERENCE_LABEL,
								IssueSeverity.ERROR, EMPTY_REFERENCE_LINK_LABEL_MESSAGE,
								1, uncheckedStart, uncheckedStart + " ".length()),
						new ValidationIssue(MarkdownIssueTypes.LINK_MISSING_REFERENCE_DEFINITION,
								IssueSeverity.ERROR, missingReferenceDefinitionMessage("x"),
								2, checkedStart, checkedStart + "x".length())),
				this.service.validateMarkdown(markdown),
				"The check box of a task list item is read as a shortcut reference link,"
						+ " so every task list item is reported. This is a known shortcoming.");
	}

	@Test
	public void reportsReferenceLinkLabelContainingATabulator() {
		String markdown = "See [some\tlabel] for details.\n";

		int expectedStart = markdown.indexOf("some\tlabel");

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_MISSING_REFERENCE_DEFINITION,
						IssueSeverity.ERROR, missingReferenceDefinitionMessage("some\tlabel"),
						1, expectedStart, expectedStart + "some\tlabel".length())),
				this.service.validateMarkdown(markdown),
				"A reference link label may contain a tabulator, which ends up in the message unchanged.");
	}

	@Test
	public void reportsReferenceLinkLabelsWithSpecialCharacters() {
		// This is the content of a document of the recorded corpus. Company internal names
		// are replaced by generic ones of the same length, so the expected offsets are
		// still the recorded ones.
		String markdown = """
				TEST_CASE [Test_somelib/Main/BasicConfigurationTest.hpp]|n/a (offline)|

				[Some component core partition] i.e., the Alpha driver has to implement many callback interfaces
				(here named *hook*-interfaces), each having

				# References

				- [ARC42.Alpha]
				- [Widget (Alpha)]
				- [some text]\s\
				""";

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_MISSING_REFERENCE_DEFINITION,
								IssueSeverity.ERROR,
								missingReferenceDefinitionMessage("Test_somelib/Main/BasicConfigurationTest.hpp"),
								1, 11, 55),
						new ValidationIssue(MarkdownIssueTypes.LINK_MISSING_REFERENCE_DEFINITION,
								IssueSeverity.ERROR,
								missingReferenceDefinitionMessage("Some component core partition"), 3, 74, 103),
						new ValidationIssue(MarkdownIssueTypes.LINK_MISSING_REFERENCE_DEFINITION,
								IssueSeverity.ERROR, missingReferenceDefinitionMessage("ARC42.Alpha"), 8, 232, 243),
						new ValidationIssue(MarkdownIssueTypes.LINK_MISSING_REFERENCE_DEFINITION,
								IssueSeverity.ERROR, missingReferenceDefinitionMessage("Widget (Alpha)"), 9, 248, 262),
						new ValidationIssue(MarkdownIssueTypes.LINK_MISSING_REFERENCE_DEFINITION,
								IssueSeverity.ERROR, missingReferenceDefinitionMessage("some text"), 10, 267, 276)),
				this.service.validateMarkdown(markdown),
				"A reference link label may contain slashes, dots, spaces and parentheses.");
	}

	@Test
	public void reportsQuotedReferenceLinkButNotEscapedBrackets() {
		// This is the content of a document of the recorded corpus. Company internal names
		// are replaced by generic ones of the same length, so the expected offsets are
		// still the recorded ones.
		String markdown = """
				this is the [unit]

				this is the \\[unit\\] with escapes

				this is the "[unit]" with quotes

				this is the "\\[unit\\]" with quotes and escapes

				https://www.example.com

				http://www.google.de

				""";

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_MISSING_REFERENCE_DEFINITION,
								IssueSeverity.ERROR, missingReferenceDefinitionMessage("unit"), 1, 13, 17),
						new ValidationIssue(MarkdownIssueTypes.LINK_MISSING_REFERENCE_DEFINITION,
								IssueSeverity.ERROR, missingReferenceDefinitionMessage("unit"), 5, 69, 73)),
				this.service.validateMarkdown(markdown),
				"Quotes around a reference link do not hide it, while escaped brackets are no link at all.");
	}

	@Test
	public void reportsInvalidLinkReferenceDefinitionIdentifier() {
		// This is the content of a document of the recorded corpus. Company internal names
		// are replaced by generic ones of the same length, so the expected offsets are
		// still the recorded ones.
		String markdown = """
				bla [(REF:ARC42.Alpha)] blubb

				<!-- External references -->
				[(REF:ARC42.Alpha)]: https://wiki.example.com/display/PROJ/Alpha\s\
				""";

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_REFERENCE_DEFINITION_INVALID_IDENTIFIER,
						IssueSeverity.ERROR,
						invalidLinkReferenceDefinitionIdentifierMessage("(REF:ARC42.Alpha)"), 4, 61, 78)),
				this.service.validateMarkdown(markdown),
				"Parentheses are not allowed in a link reference definition identifier."
						+ " Only the definition is reported, the reference to it is not,"
						+ " because it does have a definition.");
	}

	@Test
	public void acceptsLinkReferenceDefinitionIdentifierWithSpacesAndSlashes() {
		String markdown = "[bla blub 1]: https://example.com\n[a/b_c-d:e.f]: https://example.org\n";

		assertTrue(this.service.validateMarkdown(markdown).isEmpty(),
				"Letters, digits, spaces, slashes, underscores, hyphens, colons and periods are allowed.");
	}

	@Test
	public void reportsEveryDeclarationOfADuplicateLinkReferenceDefinitionIdentifier() {
		String markdown = "[bla blub 1]: https://example.com\n[bla blub 1]: https://example.org\n";

		int firstStart = markdown.indexOf("bla blub 1");
		int secondStart = markdown.indexOf("bla blub 1", firstStart + 1);

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_REFERENCE_DEFINITION_DUPLICATE_IDENTIFIER,
								IssueSeverity.ERROR,
								duplicateLinkReferenceDefinitionIdentifierMessage("bla blub 1", "1, 2"),
								1, firstStart, firstStart + "bla blub 1".length()),
						new ValidationIssue(MarkdownIssueTypes.LINK_REFERENCE_DEFINITION_DUPLICATE_IDENTIFIER,
								IssueSeverity.ERROR,
								duplicateLinkReferenceDefinitionIdentifierMessage("bla blub 1", "1, 2"),
								2, secondStart, secondStart + "bla blub 1".length())),
				this.service.validateMarkdown(markdown),
				"Both definitions are reported, and both name the lines of all definitions.");
	}

	private static String missingReferenceDefinitionMessage(String linkLabel) {		return "There is no link reference definition for the reference link label \"" + linkLabel
				+ "\". Expected a link reference definition like \"[ReferenceLinkLabel]: https://plantuml.com\"";
	}

	private static String ambiguousReferenceMessage(String linkLabel) {
		return "There is either no link reference definition for the reference link label \"" + linkLabel
				+ "\" (assuming this is a collapsed reference link like \"[ReferenceLinkLabel][]\")"
				+ " or the reference link label is empty  (assuming this is a full reference link"
				+ " like \"[Some text][ReferenceLinkLabel]\")."
				+ " Expected a link reference definition like \"[" + linkLabel + "]: https://plantuml.com\""
				+ " or a reference link \"[" + linkLabel + "][ReferenceLinkLabel]\""
				+ " to an existing link reference definition.";
	}

	private static String invalidLinkReferenceDefinitionIdentifierMessage(String identifier) {
		return "The link reference definition identifier \"" + identifier + "\" is invalid."
				// the double space is deliberate, it is part of the message this rule reports
				+ " It has to contain at least one non-space character "
				+ " and is allowed to contain any number of the following characters:"
				+ " letters ([A-Za-z]), digits ([0-9]), hyphens (\"-\"), underscores (\"_\"),"
				+ " colons (\":\"), periods (\".\"), slashes (\"/\"), spaces (\" \").";
	}

	private static String duplicateLinkReferenceDefinitionIdentifierMessage(String identifier, String lines) {
		return "The link reference definition identifier \"" + identifier + "\" is not unique."
				+ " The same identifier is used in the following lines: " + lines;
	}

}