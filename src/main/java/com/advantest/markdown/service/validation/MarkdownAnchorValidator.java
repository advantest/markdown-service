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
import java.util.stream.Collectors;

import com.advantest.markdown.service.parsing.MarkdownParsingTools;
import com.advantest.markdown.service.parsing.RegexMatch;
import com.advantest.markdown.service.utils.TextUtils;

/**
 * Checks the anchor identifiers declared in the headings of Markdown source code.
 */
class MarkdownAnchorValidator {

	private static final String MESSAGE_INVALID_ANCHOR_IDENTIFIER_SUFFIX =
			" It has to contain at least one character, must start with a letter,"
			+ " and is allowed to contain any number of the following characters in the remainder:"
			+ " letters ([A-Za-z]), digits ([0-9]), hyphens (\"-\"), underscores (\"_\"),"
			+ " colons (\":\"), and periods (\".\").";

	/**
	 * Checks the given Markdown source code.
	 * 
	 * @param markdownSourceCode the Markdown source code to be checked, must not be <code>null</code>
	 * @return the problems found, in no particular order, never <code>null</code>
	 */
	List<ValidationIssue> validate(String markdownSourceCode) {
		List<ValidationIssue> issues = new ArrayList<>();

		Map<String, List<RegexMatch>> anchorDeclarations = new LinkedHashMap<>();
		MarkdownParsingTools.findHeadingAnchorIds(markdownSourceCode)
				.forEach(match -> anchorDeclarations
						.computeIfAbsent(match.matchedText, anchorId -> new ArrayList<>(2))
						.add(match));

		anchorDeclarations.values().stream()
				.flatMap(List::stream)
				.filter(anchorIdMatch -> !MarkdownParsingTools.isValidAnchorIdentifier(anchorIdMatch.matchedText))
				.forEach(anchorIdMatch -> issues.add(invalidAnchorIdentifierIssue(anchorIdMatch, markdownSourceCode)));

		anchorDeclarations.entrySet().stream()
				.filter(anchor -> anchor.getValue().size() > 1)
				.forEach(anchor -> reportDuplicateAnchorIdentifier(anchor.getKey(), anchor.getValue(),
						markdownSourceCode, issues));

		return issues;
	}

	private ValidationIssue invalidAnchorIdentifierIssue(RegexMatch anchorIdMatch, String markdownSourceCode) {
		int startOffset = anchorIdMatch.startIndex - 1;
		int endOffset = anchorIdMatch.endIndex;

		return new ValidationIssue(MarkdownIssueTypes.ANCHOR_INVALID_IDENTIFIER, IssueSeverity.ERROR,
				"The anchor identifier \"" + anchorIdMatch.matchedText + "\" is invalid."
						+ MESSAGE_INVALID_ANCHOR_IDENTIFIER_SUFFIX,
				TextUtils.getLineNumberForOffset(markdownSourceCode, startOffset), startOffset, endOffset);
	}

	private void reportDuplicateAnchorIdentifier(String anchorId, List<RegexMatch> declarations,
			String markdownSourceCode, List<ValidationIssue> issues) {

		String lines = declarations.stream()
				.map(declaration -> TextUtils.getLineNumberForOffset(markdownSourceCode, declaration.startIndex))
				.map(String::valueOf)
				.collect(Collectors.joining(", "));

		for (RegexMatch declaration : declarations) {
			int startOffset = declaration.startIndex - 1;
			int endOffset = declaration.endIndex;

			issues.add(new ValidationIssue(MarkdownIssueTypes.ANCHOR_DUPLICATE_IDENTIFIER, IssueSeverity.ERROR,
					"The anchor identifier \"" + anchorId + "\" is not unique."
							+ " The same identifier is used in the following lines: " + lines,
					TextUtils.getLineNumberForOffset(markdownSourceCode, startOffset), startOffset, endOffset));
		}
	}

}
