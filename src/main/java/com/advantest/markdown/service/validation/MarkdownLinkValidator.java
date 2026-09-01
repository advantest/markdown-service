/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.advantest.markdown.service.parsing.MarkdownParsingTools;
import com.advantest.markdown.service.parsing.RegexMatch;
import com.advantest.markdown.service.utils.TextUtils;

/**
 * Checks the links, images and link reference definitions in Markdown source code.
 */
class MarkdownLinkValidator {

	private static final String MESSAGE_EMPTY_LINK_TARGET = "The target file path or URL is empty.";

	private static final String MESSAGE_EMPTY_REFERENCE_LINK_LABEL =
			"The reference link label is empty. Please create a link reference definition like"
			+ " \"[ReferenceLinkLabel]: https://plantuml.com\""
			+ " and use that reference link label in your link,"
			+ " e.g. \"[your link text][ReferenceLinkLabel]\" or \"[ReferenceLinkLabel]\".";

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

		Stream.concat(
				MarkdownParsingTools.findFullAndCollapsedReferenceLinks(markdownSourceCode),
				MarkdownParsingTools.findShortcutReferenceLinks(markdownSourceCode))
				.forEach(referenceLink -> checkReferenceLinkLabel(referenceLink, markdownSourceCode, issues));

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

	private void checkReferenceLinkLabel(RegexMatch referenceLink, String markdownSourceCode,
			List<ValidationIssue> issues) {

		RegexMatch targetMatch = referenceLink.subMatches.get(MarkdownParsingTools.CAPTURING_GROUP_TARGET);
		if (targetMatch == null) {
			return;
		}
		RegexMatch labelMatch = referenceLink.subMatches.get(MarkdownParsingTools.CAPTURING_GROUP_LABEL);

		// first, assume a full reference link like [link text][linkLabel]
		// or a shortcut reference link like [linkLabel]
		String linkLabel = targetMatch.matchedText;

		// in some rare cases we have a collapsed reference link like [linkLabel][]
		boolean collapsedReferenceLink = false;
		if (linkLabel.isEmpty() && labelMatch != null && !labelMatch.matchedText.isBlank()) {
			linkLabel = labelMatch.matchedText;
			collapsedReferenceLink = true;
		}

		if (linkLabel.isBlank()) {
			int startOffset = targetMatch.startIndex;
			int endOffset = startOffset + linkLabel.length();

			if (linkLabel.isEmpty()) {
				// there is nothing to mark, so mark the brackets surrounding the label as well
				startOffset--;
				endOffset++;
			}

			issues.add(new ValidationIssue(
					MarkdownIssueTypes.LINK_EMPTY_REFERENCE_LABEL,
					IssueSeverity.ERROR,
					MESSAGE_EMPTY_REFERENCE_LINK_LABEL,
					TextUtils.getLineNumberForOffset(markdownSourceCode, startOffset),
					startOffset,
					endOffset));
			return;
		}

		if (MarkdownParsingTools.findLinkReferenceDefinition(markdownSourceCode, linkLabel).isPresent()) {
			return;
		}

		if (collapsedReferenceLink) {
			// an empty full reference link looks like a collapsed reference link,
			// so we cannot tell which of the two problems it is:
			// full reference link:       [Some text][RefID]
			// collapsed reference link:  [RefID][]
			// empty full reference link: [Some text][]
			int startOffset = referenceLink.startIndex;
			int endOffset = startOffset + referenceLink.matchedText.length();

			issues.add(new ValidationIssue(
					MarkdownIssueTypes.LINK_AMBIGUOUS_REFERENCE,
					IssueSeverity.ERROR,
					ambiguousReferenceMessage(linkLabel),
					TextUtils.getLineNumberForOffset(markdownSourceCode, startOffset),
					startOffset,
					endOffset));
		} else {
			int startOffset = targetMatch.startIndex;
			int endOffset = startOffset + linkLabel.length();

			issues.add(new ValidationIssue(
					MarkdownIssueTypes.LINK_MISSING_REFERENCE_DEFINITION,
					IssueSeverity.ERROR,
					missingReferenceDefinitionMessage(linkLabel),
					TextUtils.getLineNumberForOffset(markdownSourceCode, startOffset),
					startOffset,
					endOffset));
		}
	}

	private static String missingReferenceDefinitionMessage(String linkLabel) {
		return "There is no link reference definition for the reference link label \"" + linkLabel
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

}
