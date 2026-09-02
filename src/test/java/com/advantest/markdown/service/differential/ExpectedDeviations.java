/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.differential;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import com.advantest.markdown.service.validation.MarkdownIssueTypes;

/**
 * The differences between this service and the recorded run that are meant to be there.
 * 
 * <p>The extraction reproduces what FluentMark reports, so every difference is a failure — except
 * the ones that were decided, which are listed here and in <code>deviations-from-fluentmark.md</code>
 * of the <code>markdown-lsp-work</code> repository under the identifiers used below. Declaring a
 * deviation takes it out of the comparison and counts it in the report, so the comparison keeps
 * failing on everything that was not decided.</p>
 * 
 * <p>A deviation is stated as the property that makes a recorded finding one of its instances, not
 * as a list of file names and offsets. That keeps the corpus out of this repository, which is not
 * allowed to name it, and it makes the declaration cover a document that is added to the corpus
 * later on.</p>
 */
final class ExpectedDeviations {

	/**
	 * A decided difference. The identifier is the one of the deviation document, the description is
	 * what the report prints.
	 */
	record Deviation(String id, String description) {
	}

	/**
	 * A recorded finding, reduced to what a deviation needs to recognise it.
	 */
	record RecordedFindingInContext(String issueTypeId, int startOffset, int endOffset, String sourceCode) {

		/**
		 * The part of the document the finding marks.
		 */
		String markedText() {
			int start = Math.max(0, Math.min(this.startOffset, this.sourceCode.length()));
			int end = Math.max(start, Math.min(this.endOffset, this.sourceCode.length()));

			return this.sourceCode.substring(start, end);
		}

		/**
		 * The line the finding starts in, without its line separator.
		 */
		String line() {
			return lineEndingAt(Math.min(this.startOffset, this.sourceCode.length()));
		}

		/**
		 * The last line before the one the finding starts in that holds anything but whitespace, or
		 * an empty string if there is none.
		 */
		String previousNonEmptyLine() {
			int lineStart = startOfLineAt(Math.min(this.startOffset, this.sourceCode.length()));

			while (lineStart > 0) {
				String line = lineEndingAt(lineStart - 1);
				if (!line.isBlank()) {
					return line;
				}
				lineStart = startOfLineAt(lineStart - 1);
			}

			return "";
		}

		private String lineEndingAt(int offset) {
			int start = startOfLineAt(offset);
			int end = start;

			while (end < this.sourceCode.length() && this.sourceCode.charAt(end) != '\n'
					&& this.sourceCode.charAt(end) != '\r') {
				end++;
			}

			return this.sourceCode.substring(start, end);
		}

		private int startOfLineAt(int offset) {
			int start = Math.max(0, Math.min(offset, this.sourceCode.length()));

			while (start > 0 && this.sourceCode.charAt(start - 1) != '\n'
					&& this.sourceCode.charAt(start - 1) != '\r') {
				start--;
			}

			return start;
		}
	}

	private record DeclaredDeviation(Deviation deviation, Predicate<RecordedFindingInContext> recognises) {
	}

	private static final List<DeclaredDeviation> ABSENCES = List.of(
			new DeclaredDeviation(
					new Deviation("V-02", "a footnote reference is a footnote, not a reference link"),
					finding -> finding.issueTypeId().equals(MarkdownIssueTypes.LINK_MISSING_REFERENCE_DEFINITION)
							&& finding.markedText().startsWith("^")),
			new DeclaredDeviation(
					new Deviation("V-04", "a bracket expression below a table is its caption,"
							+ " not a reference link"),
					finding -> finding.issueTypeId().equals(MarkdownIssueTypes.LINK_MISSING_REFERENCE_DEFINITION)
							&& finding.line().strip().matches("\\[[^\\[\\]]*\\]")
							&& finding.previousNonEmptyLine().strip().startsWith("|")));

	/**
	 * Tells whether a recorded finding is one this service is meant not to produce, and which
	 * decision says so.
	 * 
	 * @param finding the recorded finding together with the document it was recorded for
	 * @return the deviation explaining the absence, or empty if the finding is expected
	 */
	static Optional<Deviation> explainingAbsenceOf(RecordedFindingInContext finding) {
		return ABSENCES.stream()
				.filter(declared -> declared.recognises().test(finding))
				.map(DeclaredDeviation::deviation)
				.findFirst();
	}

	private ExpectedDeviations() {
		// utility class, not meant to be instantiated
	}

}
