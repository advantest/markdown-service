/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.resource;

import com.advantest.markdown.service.validation.IssueSeverity;
import com.advantest.markdown.service.validation.MarkdownIssueTypes;
import com.advantest.markdown.service.validation.ValidationIssue;

import java.util.List;

import com.advantest.markdown.service.parsing.LinkTarget;
import com.advantest.markdown.service.parsing.RegexMatch;
import com.advantest.markdown.service.utils.TextUtils;
import com.vladsch.flexmark.util.ast.Document;

/**
 * Reports a target that names its resource on its own, e.g. <code>/usr/share/doc/guide.md</code>,
 * <code>C:\documents\guide.md</code> or <code>\\server\share\guide.md</code>.
 * 
 * <p>Such a target is not looked for. It leads to the resource the author meant on the machine the
 * document was written on and nowhere else: a document is read by others, on other machines, with
 * other directories and often with another file system altogether, and the path that named the
 * resource there names nothing here. Whether it happens to name something is beside the point,
 * because a target that works by coincidence is still the wrong target.</p>
 * 
 * <p>What is right instead is a path as seen from the document, e.g.
 * <code>../guide/introduction.md</code>. It travels with the document, so it leads to the same
 * resource for every reader.</p>
 */
public class AbsolutePathValidator {

	/**
	 * Reports the given target of a link, an image or a link reference definition, which names its
	 * resource on its own.
	 * 
	 * @param targetMatch the target as it stands in the document, written as a path naming its
	 *                    resource on its own, must not be <code>null</code>
	 * @param document the document the target is written in, must not be <code>null</code>
	 * @param issues the problems found so far, to which this validator adds its own, must not be
	 *               <code>null</code>
	 */
	public void checkTargetPath(RegexMatch targetMatch, Document document, List<ValidationIssue> issues) {
		LinkTarget target = LinkTarget.of(targetMatch.matchedText);
		if (target.path() == null || target.path().isBlank()) {
			return;
		}

		int startOffset = targetMatch.startIndex;
		int endOffset = startOffset + target.path().length();

		issues.add(new ValidationIssue(
				MarkdownIssueTypes.LINK_ABSOLUTE_TARGET_PATH,
				IssueSeverity.WARNING,
				absoluteTargetPathMessage(target.path()),
				TextUtils.getLineNumberForOffset(document, startOffset),
				startOffset,
				endOffset));
	}

	private static String absoluteTargetPathMessage(String targetPath) {
		return String.format("The path '%s' names a file or directory of one machine, so it leads"
				+ " nowhere for anybody else reading this document. Please use a path relative to"
				+ " this document instead.", targetPath);
	}

}
