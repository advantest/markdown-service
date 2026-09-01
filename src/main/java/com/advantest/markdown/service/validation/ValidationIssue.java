/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

/**
 * A single problem found in Markdown source code, e.g. a link that points nowhere.
 * 
 * <p>An issue always knows where it was found. The offsets refer to the Markdown source code
 * that was validated, counted in characters from its beginning, and they denote the text range
 * a tool should highlight. The line number is redundant to the offsets and is provided because
 * most consumers need it.</p>
 * 
 * @param issueTypeId identifies what kind of problem this is, must neither be <code>null</code>
 *        nor blank, see {@link MarkdownIssueTypes}
 * @param severity how severe the problem is, must not be <code>null</code>
 * @param message describes the problem in a way an author can act on,
 *        must neither be <code>null</code> nor blank
 * @param lineNumber the line the problem was found in, starting at 1
 * @param startOffset the first character of the problematic text range, starting at 0, inclusive
 * @param endOffset the character following the problematic text range, exclusive,
 *        must not be smaller than the start offset
 */
public record ValidationIssue(
		String issueTypeId,
		IssueSeverity severity,
		String message,
		int lineNumber,
		int startOffset,
		int endOffset) {

	/**
	 * Creates a validation issue, rejecting incomplete or contradictory data.
	 * 
	 * @throws IllegalArgumentException if an argument is <code>null</code>, blank
	 *         or outside its allowed range
	 */
	public ValidationIssue {
		if (issueTypeId == null || issueTypeId.isBlank()) {
			throw new IllegalArgumentException("An issue type ID is required.");
		}
		if (severity == null) {
			throw new IllegalArgumentException("A severity is required.");
		}
		if (message == null || message.isBlank()) {
			throw new IllegalArgumentException("A message is required.");
		}
		if (lineNumber < 1) {
			throw new IllegalArgumentException("Line numbers start at 1, but was: " + lineNumber);
		}
		if (startOffset < 0) {
			throw new IllegalArgumentException("Offsets start at 0, but the start offset was: " + startOffset);
		}
		if (endOffset < startOffset) {
			throw new IllegalArgumentException(
					"The end offset " + endOffset + " is smaller than the start offset " + startOffset + ".");
		}
	}

}
