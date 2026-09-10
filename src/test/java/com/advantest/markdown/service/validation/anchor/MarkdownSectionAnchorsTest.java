/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.anchor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.markdown.service.parsing.RegexMatch;
import com.vladsch.flexmark.util.ast.Document;

/**
 * Checks which anchors a parsed Markdown document is said to declare.
 */
class MarkdownSectionAnchorsTest {

	private final MarkdownParserAndHtmlRenderer parserAndRenderer = new MarkdownParserAndHtmlRenderer();

	@Test
	void aHeadingDeclaresTheAnchorWrittenAtItsEnd() {
		Document document = parse("# Overview {#overview}\n\n## Details {#details}\n");

		assertEquals(Set.of("overview", "details"), MarkdownSectionAnchors.validAnchorsIn(document));
	}

	@Test
	void aHeadingWithoutAnAnchorDeclaresNone() {
		Document document = parse("# Overview\n\nSome text about {#nothing} in a paragraph.\n");

		assertTrue(MarkdownSectionAnchors.validAnchorsIn(document).isEmpty(),
				"An anchor is declared by a heading, not by a pair of braces somewhere.");
	}

	@Test
	void aHeadingInsideACodeBlockDeclaresNoAnchor() {
		Document document = parse("Look at this:\n\n```\n# Overview {#overview}\n```\n");

		assertTrue(MarkdownSectionAnchors.validAnchorsIn(document).isEmpty(),
				"Inside a code block a line beginning with a hash is text and no heading.");
	}

	@Test
	void anIdentifierThatIsNotAValidOneIsNoAnchorToPointAt() {
		Document document = parse("# Overview {#1-overview}\n");

		assertTrue(MarkdownSectionAnchors.validAnchorsIn(document).isEmpty(),
				"A link to an invalid identifier would not lead anywhere either.");
		assertEquals(Set.of("1-overview"), MarkdownSectionAnchors.declarationsIn(document).keySet(),
				"That the declaration is there is still said, so a rule about it can point at it.");
	}

	@Test
	void anIdentifierDeclaredTwiceIsKeptTwice() {
		Document document = parse("# Overview {#overview}\n\n## More {#overview}\n");

		Map<String, List<RegexMatch>> declarations = MarkdownSectionAnchors.declarationsIn(document);

		assertEquals(Set.of("overview"), declarations.keySet());
		assertEquals(2, declarations.get("overview").size(),
				"Both places declaring the same identifier are expected to be kept.");
	}

	@Test
	void anAnchorInsideAQuotedSectionIsFoundAsWell() {
		Document document = parse("> # Quoted {#quoted}\n");

		assertEquals(Set.of("quoted"), MarkdownSectionAnchors.validAnchorsIn(document),
				"A heading is a heading wherever it stands.");
	}

	@Test
	void aLinkMayPointToEveryAnchorOfAHeadingDeclaringSeveral() {
		Document document = parse("# Overview {#first} {#second}\n");

		assertEquals(Set.of("first", "second"), MarkdownSectionAnchors.validAnchorsIn(document),
				"What the parser read as an identifier is an anchor a link leads to.");
	}

	@Test
	void whatTheParserDoesNotReadAsAnIdentifierIsStillReadAsADeclaration() {
		Document document = parse("# Overview {#two words}\n");

		assertTrue(MarkdownSectionAnchors.validAnchorsIn(document).contains("two"),
				"The parser reads the first word as the identifier, and so a link leads there.");
		assertEquals(Set.of("two words"), MarkdownSectionAnchors.declarationsIn(document).keySet(),
				"What the author wrote is kept, so that a rule can tell them that it is no identifier.");
	}

	@Test
	void nothingIsAnsweredAboutNoDocument() {
		assertThrows(IllegalArgumentException.class, () -> MarkdownSectionAnchors.declarationsIn(null));
		assertThrows(IllegalArgumentException.class, () -> MarkdownSectionAnchors.validAnchorsIn(null));
	}

	private Document parse(String markdown) {
		return this.parserAndRenderer.parseMarkdown(markdown);
	}

}
