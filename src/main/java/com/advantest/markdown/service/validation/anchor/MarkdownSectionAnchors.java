/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.anchor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.advantest.markdown.service.parsing.MarkdownParsingTools;
import com.advantest.markdown.service.parsing.RegexMatch;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;

/**
 * The anchors a parsed Markdown document declares, i.e. the <code>{#identifier}</code> at the end
 * of a heading.
 * 
 * <p>They are read from the parsed document and not from its lines, so that a heading inside a
 * fenced code block is no heading and the identifier is read as what it is rather than as the last
 * pair of braces on a line. A document a link points into is therefore answered about the same way
 * as the document being checked.</p>
 * 
 * <p>An identifier written in embedded HTML, e.g. <code>&lt;a id="identifier"&gt;</code>, declares
 * no anchor: a reader looking for the anchors of a document reads its headings.</p>
 */
public final class MarkdownSectionAnchors {

	private MarkdownSectionAnchors() {
		// this class offers nothing but static methods
	}

	/**
	 * Collects every anchor declaration of the given document, including the invalid one and the
	 * one declared twice, so that a rule about the declarations themselves can say where they
	 * stand.
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
		collectDeclarations(document, declarations);
		return declarations;
	}

	/**
	 * Tells which anchors of the given document a link may point to.
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
		return declarationsIn(document).keySet().stream()
				.filter(MarkdownParsingTools::isValidAnchorIdentifier)
				.collect(Collectors.toSet());
	}

	private static void collectDeclarations(Node node, Map<String, List<RegexMatch>> declarations) {
		for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
			if (child instanceof Heading) {
				MarkdownParsingTools
						.findHeadingAnchorIds(node.getDocument().getChars(), child.getStartOffset(),
								child.getEndOffset())
						.forEach(match -> declarations
								.computeIfAbsent(match.matchedText, anchorId -> new ArrayList<>(2))
								.add(match));
			} else {
				collectDeclarations(child, declarations);
			}
		}
	}

}
