/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.advantest.markdown.service.parsing.MarkdownParsingTools;
import com.advantest.markdown.service.parsing.RegexMatch;
import com.advantest.markdown.service.utils.TextUtils;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;

/**
 * Checks the anchor identifiers declared in the headings of Markdown source code.
 * 
 * <p>An anchor is declared by <code>{#identifier}</code> at the end of a heading. An identifier
 * written in embedded HTML, e.g. <code>&lt;a id="identifier"&gt;</code>, does not declare one:
 * a reader looking for the anchors of a document reads its headings.</p>
 * 
 * <p>Whether an identifier is used twice can only be told from the whole document, so this
 * validator is triggered by the document and looks at its headings.</p>
 */
class MarkdownAnchorValidator implements MarkdownValidator {

	private static final String MESSAGE_INVALID_ANCHOR_IDENTIFIER_SUFFIX =
			" It has to contain at least one character, must start with a letter,"
			+ " and is allowed to contain any number of the following characters in the remainder:"
			+ " letters ([A-Za-z]), digits ([0-9]), hyphens (\"-\"), underscores (\"_\"),"
			+ " colons (\":\"), and periods (\".\").";

	@Override
	public Set<Class<? extends Node>> getTriggeringNodeTypes() {
		return Set.of(Document.class);
	}

	@Override
	public List<ValidationIssue> validate(Node node, MarkdownValidationContext context) {
		Document document = node.getDocument();
		List<ValidationIssue> issues = new ArrayList<>();

		Map<String, List<RegexMatch>> anchorDeclarations = new LinkedHashMap<>();
		collectAnchorDeclarations(node, anchorDeclarations);

		anchorDeclarations.values().stream()
				.flatMap(List::stream)
				.filter(anchorIdMatch -> !MarkdownParsingTools.isValidAnchorIdentifier(anchorIdMatch.matchedText))
				.forEach(anchorIdMatch -> issues.add(invalidAnchorIdentifierIssue(anchorIdMatch, document)));

		anchorDeclarations.entrySet().stream()
				.filter(anchor -> anchor.getValue().size() > 1)
				.forEach(anchor -> reportDuplicateAnchorIdentifier(anchor.getKey(), anchor.getValue(),
						document, issues));

		return issues;
	}

	private static void collectAnchorDeclarations(Node node,
			Map<String, List<RegexMatch>> anchorDeclarations) {

		for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
			if (child instanceof Heading) {
				MarkdownParsingTools
						.findHeadingAnchorIds(node.getDocument().getChars(), child.getStartOffset(), child.getEndOffset())
						.forEach(match -> anchorDeclarations
								.computeIfAbsent(match.matchedText, anchorId -> new ArrayList<>(2))
								.add(match));
			} else {
				collectAnchorDeclarations(child, anchorDeclarations);
			}
		}
	}

	private ValidationIssue invalidAnchorIdentifierIssue(RegexMatch anchorIdMatch, Document document) {
		int startOffset = anchorIdMatch.startIndex - 1;
		int endOffset = anchorIdMatch.endIndex;

		return new ValidationIssue(MarkdownIssueTypes.ANCHOR_INVALID_IDENTIFIER, IssueSeverity.ERROR,
				"The anchor identifier \"" + anchorIdMatch.matchedText + "\" is invalid."
						+ MESSAGE_INVALID_ANCHOR_IDENTIFIER_SUFFIX,
				TextUtils.getLineNumberForOffset(document, startOffset), startOffset, endOffset);
	}

	private void reportDuplicateAnchorIdentifier(String anchorId, List<RegexMatch> declarations,
			Document document, List<ValidationIssue> issues) {

		String lines = declarations.stream()
				.map(declaration -> TextUtils.getLineNumberForOffset(document, declaration.startIndex))
				.map(String::valueOf)
				.collect(Collectors.joining(", "));

		for (RegexMatch declaration : declarations) {
			int startOffset = declaration.startIndex - 1;
			int endOffset = declaration.endIndex;

			issues.add(new ValidationIssue(MarkdownIssueTypes.ANCHOR_DUPLICATE_IDENTIFIER, IssueSeverity.ERROR,
					"The anchor identifier \"" + anchorId + "\" is not unique."
							+ " The same identifier is used in the following lines: " + lines,
					TextUtils.getLineNumberForOffset(document, startOffset), startOffset, endOffset));
		}
	}

}