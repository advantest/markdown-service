/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import java.util.ArrayList;
import java.util.List;

import com.advantest.markdown.service.parsing.MarkdownParsingTools;
import com.advantest.markdown.service.parsing.RegexMatch;

/**
 * Checks the links, images and link reference definitions in Markdown source code.
 */
class MarkdownLinkValidator {

	private static final String MESSAGE_EMPTY_LINK_TARGET = "The target file path or URL is empty.";

	/**
	 * Checks the given Markdown source code.
	 * 
	 * @param markdownSourceCode the Markdown source code to be checked, must not be <code>null</code>
	 * @return the problems found, in no particular order, never <code>null</code>
	 */
	List<ValidationIssue> validate(String markdownSourceCode) {
		List<ValidationIssue> issues = new ArrayList<>();

		MarkdownParsingTools.findLinksAndImages(markdownSourceCode)
				.forEach(link -> checkLinkTarget(link, markdownSourceCode, false, issues));
		MarkdownParsingTools.findLinkReferenceDefinitions(markdownSourceCode)
				.forEach(definition -> checkLinkTarget(definition, markdownSourceCode, true, issues));

		return issues;
	}

	private void checkLinkTarget(RegexMatch linkStatement, String markdownSourceCode,
			boolean targetInLinkReferenceDefinition, List<ValidationIssue> issues) {

		RegexMatch targetMatch = linkStatement.subMatches.get(MarkdownParsingTools.CAPTURING_GROUP_TARGET);
		if (targetMatch == null) {
			return;
		}

		String linkTarget = targetMatch.matchedText;
		if (!linkTarget.isBlank()) {
			return;
		}

		int startOffset = targetMatch.startIndex;
		int endOffset = startOffset + linkTarget.length();

		if (linkTarget.isEmpty()) {
			if (targetInLinkReferenceDefinition) {
				// there is nothing to mark, so mark the link reference definition statement instead
				startOffset = linkStatement.startIndex;
			} else {
				// there is nothing to mark, so mark the brackets surrounding the target as well
				startOffset--;
				endOffset++;
			}
		}

		issues.add(new ValidationIssue(
				MarkdownIssueTypes.LINK_EMPTY_TARGET,
				IssueSeverity.ERROR,
				MESSAGE_EMPTY_LINK_TARGET,
				TextUtils.getLineNumberForOffset(markdownSourceCode, startOffset),
				startOffset,
				endOffset));
	}

}
