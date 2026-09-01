/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.advantest.markdown.service.parsing.MarkdownParsingTools;
import com.advantest.markdown.service.parsing.RegexMatch;
import com.advantest.markdown.service.validation.IssueSeverity;
import com.advantest.markdown.service.validation.MarkdownIssueTypes;
import com.advantest.markdown.service.validation.ValidationIssue;

/**
 * Applies the validation rules to Markdown source code.
 * 
 * <p>The rules reproduce those of the FluentMark Eclipse plug-ins, including their messages and
 * the text ranges they mark, so that both report the same problems for the same document.</p>
 */
class MarkdownValidator {

	private static final String MESSAGE_EMPTY_LINK_TARGET = "The target file path or URL is empty.";

	/**
	 * Checks the given Markdown source code.
	 * 
	 * @param markdownSourceCode the Markdown source code to be checked, must not be <code>null</code>
	 * @return the problems found, ordered by start offset, never <code>null</code> and not modifiable
	 */
	List<ValidationIssue> validate(String markdownSourceCode) {
		List<ValidationIssue> issues = new ArrayList<>();

		MarkdownParsingTools.findLinksAndImages(markdownSourceCode)
				.forEach(link -> checkLinkTarget(link, markdownSourceCode, false, issues));
		MarkdownParsingTools.findLinkReferenceDefinitions(markdownSourceCode)
				.forEach(definition -> checkLinkTarget(definition, markdownSourceCode, true, issues));

		issues.sort(Comparator.comparingInt(ValidationIssue::startOffset));
		return List.copyOf(issues);
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
				lineNumberAt(markdownSourceCode, startOffset),
				startOffset,
				endOffset));
	}

	/**
	 * Determines the number of the line the given offset points into, counting line breaks the way
	 * an Eclipse document does, i.e. <code>\n</code>, <code>\r\n</code> and <code>\r</code> all end
	 * a line.
	 * 
	 * @param text the text to count in
	 * @param offset the offset to determine the line for
	 * @return the line number, starting at 1
	 */
	private static int lineNumberAt(String text, int offset) {
		int lineNumber = 1;
		int lastOffsetToLookAt = Math.min(offset, text.length());

		for (int index = 0; index < lastOffsetToLookAt; index++) {
			char character = text.charAt(index);

			if (character == '\n') {
				lineNumber++;
			} else if (character == '\r'
					&& (index + 1 >= text.length() || text.charAt(index + 1) != '\n')) {
				lineNumber++;
			}
		}

		return lineNumber;
	}

}
