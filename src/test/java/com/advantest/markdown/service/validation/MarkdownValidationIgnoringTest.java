/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.advantest.markdown.service.MarkdownService;

/**
 * Checks that the built-in validators do not look into the parts of a document that are not Markdown
 * code, using one document per embedded language. Every document holds the same broken link
 * {@code [broken]()}, which is reported when it is read as Markdown and is not reported when it is
 * not.
 */
public class MarkdownValidationIgnoringTest {

	private static final String BROKEN_LINK = "[broken]()";

	private final MarkdownService service = new MarkdownService();

	private void assertNotReported(String markdown, String reason) {
		assertEquals(List.of(), this.service.validateMarkdown(markdown), reason);
	}

	private void assertReported(String markdown, String reason) {
		List<ValidationIssue> issues = this.service.validateMarkdown(markdown);

		assertEquals(1, issues.size(), reason + " Reported: " + issues);
		assertTrue(issues.get(0).issueTypeId().endsWith("emptyTarget"), reason + " Reported: " + issues);
	}

	@Test
	public void aLinkInPlainMarkdownIsReported() {
		assertReported("Some text with " + BROKEN_LINK + " in it.\n",
				"Without anything around it, the broken link is what every other test compares against.");
	}

	@Test
	public void aLinkInAnInlineCodeSpanIsNotReported() {
		assertNotReported("Some text with `" + BROKEN_LINK + "` in it.\n",
				"A code span holds verbatim text, not Markdown code.");
	}

	@Test
	public void aLinkInAFencedCodeBlockIsNotReported() {
		assertNotReported("Some text.\n\n```java\n" + BROKEN_LINK + "\n```\n",
				"A fenced code block holds source code of another language, not Markdown code.");
	}

	@Test
	public void aLinkInAnIndentedCodeBlockIsNotReported() {
		assertNotReported("Some text.\n\n    " + BROKEN_LINK + "\n",
				"An indented code block holds verbatim text, not Markdown code.");
	}

	@Test
	public void aLinkInAnHtmlCommentIsNotReported() {
		assertNotReported("Some text.\n\n<!-- " + BROKEN_LINK + " -->\n",
				"A comment is not rendered, so what it holds is not checked either.");
	}

	@Test
	public void aLinkInAnHtmlBlockIsNotReported() {
		assertNotReported("Some text.\n\n<div>\n" + BROKEN_LINK + "\n</div>\n",
				"An HTML block is embedded HTML, and the parser reads everything in it as HTML.");
	}

	@Test
	public void aLinkBetweenInlineHtmlTagsIsReported() {
		assertReported("Some text with <em>" + BROKEN_LINK + "</em> in it.\n",
				"Only the tags themselves are HTML. What stands between them is Markdown code and is"
						+ " therefore checked.");
	}

	@Test
	public void aLinkInAPlantUmlBlockIsNotReported() {
		assertNotReported("Some text.\n\n@startuml\ntitle " + BROKEN_LINK + "\n@enduml\n",
				"A PlantUML block holds PlantUML code, not Markdown code.");
	}

	@Test
	public void aLinkInAFencedPlantUmlBlockIsNotReported() {
		assertNotReported("Some text.\n\n```plantuml\n@startuml\ntitle " + BROKEN_LINK + "\n@enduml\n```\n",
				"A fenced PlantUML block holds PlantUML code, not Markdown code.");
	}

	@Test
	public void aLinkInAnInlineFormulaIsNotReported() {
		assertNotReported("Some text with $" + BROKEN_LINK + "$ in it.\n",
				"A formula is not Markdown code. The parser reads its content as Markdown all the same,"
						+ " so ignoring the formula node is what keeps the link away.");
	}

	@Test
	public void aLinkInAFormulaClosedOnTheSameLineIsNotReported() {
		assertNotReported("Some text.\n\n$$" + BROKEN_LINK + "\nx^2$$\n",
				"A display formula is not Markdown code.");
	}

	@Test
	public void aLinkInAFormulaClosedOnItsOwnLineIsReportedAsAKnownDeviation() {
		assertReported("Some text.\n\n$$\n" + BROKEN_LINK + "\n$$\n",
				"The parser does not recognise a display formula whose closing delimiter starts a line,"
						+ " so there is no formula node to ignore and the paragraph is checked. This test"
						+ " documents the shortcoming and is inverted once the parser is fixed.");
	}
}
