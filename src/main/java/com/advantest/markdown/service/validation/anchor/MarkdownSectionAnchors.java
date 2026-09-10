/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.anchor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.advantest.markdown.service.parsing.MarkdownParsingTools;
import com.advantest.markdown.service.parsing.RegexMatch;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ext.attributes.AttributeNode;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;

/**
 * The anchors a parsed Markdown document declares, i.e. the <code>{#identifier}</code> at the end
 * of a heading.
 * 
 * <p>Where a heading is, is read from the parsed document and never from its lines: a heading
 * inside a fenced code block is no heading, and an anchor written on anything but a heading declares
 * none &ndash; a reader looking for the anchors of a document reads its headings. An identifier
 * written in embedded HTML, e.g. <code>&lt;a id="identifier"&gt;</code>, declares no anchor
 * either.</p>
 * 
 * <p>Two different questions are asked about a heading, and each of them is answered where its
 * answer is: {@link #validAnchorsIn(Document)} tells which anchors a link may point to, so it asks
 * the parser, which is what the renderer will act on; {@link #declarationsIn(Document)} tells what
 * an author wrote as a declaration, which has to be read from the text, because a declaration the
 * parser rejected is exactly the one a rule about declarations has to point at.</p>
 */
public final class MarkdownSectionAnchors {

	private MarkdownSectionAnchors() {
		// this class offers nothing but static methods
	}

	/**
	 * Collects every anchor declaration written in a heading of the given document, including the
	 * invalid one and the one declared twice, so that a rule about the declarations themselves can
	 * say where they stand.
	 * 
	 * <p>What is written is read here and not what the parser made of it: an identifier such as
	 * <code>{#}</code> or <code>{#two words}</code> is no anchor to the parser, which drops it,
	 * while an author writing it meant to declare one and is told that it does not work.</p>
	 * 
	 * @param document the parsed document to be read, must not be <code>null</code>
	 * @return the declarations by the identifier they declare, in the order of their first
	 *         appearance, never <code>null</code>
	 * @throws IllegalArgumentException if the given document is <code>null</code>
	 */
	public static Map<String, List<RegexMatch>> declarationsIn(Document document) {
		if (document == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}

		Map<String, List<RegexMatch>> declarations = new LinkedHashMap<>();
		forEachHeading(document, heading -> MarkdownParsingTools
				.findHeadingAnchorIds(document.getChars(), heading.getStartOffset(), heading.getEndOffset())
				.forEach(match -> declarations
						.computeIfAbsent(match.matchedText, anchorId -> new ArrayList<>(2))
						.add(match)));
		return declarations;
	}

	/**
	 * Tells which anchors of the given document a link may point to.
	 * 
	 * <p>The answer is the one the parser gives, so that a link is checked against the anchors a
	 * reader will find in the rendered document, and a link into another document is answered the
	 * same way as one into the document being checked.</p>
	 * 
	 * <p>An identifier that is not a valid one is left out: a link to it would not lead there
	 * either, and saying that the anchor is not there is the truth a reader of the link needs. That
	 * the declaration itself is wrong is said where the declaration stands.</p>
	 * 
	 * @param document the parsed document to be read, must not be <code>null</code>
	 * @return the valid anchor identifiers of the document, never <code>null</code>
	 * @throws IllegalArgumentException if the given document is <code>null</code>
	 */
	public static Set<String> validAnchorsIn(Document document) {
		if (document == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}

		Set<String> anchors = new LinkedHashSet<>();
		forEachHeading(document, heading -> heading.getDescendants().forEach(node -> {
			if (node instanceof AttributeNode attribute && attribute.isId()) {
				anchors.add(attribute.getValue().toString());
			}
		}));

		return anchors.stream()
				.filter(MarkdownParsingTools::isValidAnchorIdentifier)
				.collect(Collectors.toSet());
	}

	private static void forEachHeading(Node node, Consumer<Node> headingReader) {
		for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
			if (child instanceof Heading) {
				headingReader.accept(child);
			} else {
				forEachHeading(child, headingReader);
			}
		}
	}

}
