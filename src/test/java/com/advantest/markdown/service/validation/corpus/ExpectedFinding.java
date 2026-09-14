/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.corpus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a document of the corpus says the service has to report about it.
 * 
 * <p>A declaration stands directly above the line it is about, so that it moves with that line and
 * says what it means without counting lines:</p>
 * 
 * <pre>
 * &lt;!-- expect: link.emptyTarget | ERROR | 1 | [a link]() | The link has no target. --&gt;
 * [a link]()
 * </pre>
 * 
 * <p>A finding is placed by its line, by the column it starts in and by the text it covers, rather
 * than by the offset it starts at, so that a document says the same thing whether it is stored with
 * one line ending or the other.</p>
 */
record ExpectedFinding(int lineNumber, int column, String coveredText, String severity, String issueTypeId,
		String message) {

	private static final String DECLARATION_START = "<!-- expect:";
	private static final String DECLARATION_END = "-->";
	private static final String SEPARATOR = "|";
	private static final String ISSUE_TYPE_PREFIX = "com.advantest.markdown.";

	/** What a document says about itself, standing on a line of its own. */
	static final String WITHOUT_DOCUMENT_LOCATION = "<!-- validated without a document location -->";

	/**
	 * Reads what the given document says the service has to report about it.
	 * 
	 * @param markdownSourceCode the document as it is stored, must not be <code>null</code>
	 * @return the declared findings, in the order of the document, never <code>null</code>
	 * @throws IllegalArgumentException if a declaration cannot be read
	 */
	static List<ExpectedFinding> declaredIn(String markdownSourceCode) {
		List<ExpectedFinding> expectedFindings = new ArrayList<>();
		List<String> lines = List.of(markdownSourceCode.split("\n", -1));
		List<String> declarationsWaitingForTheirLine = new ArrayList<>();

		for (int index = 0; index < lines.size(); index++) {
			String line = lines.get(index);

			if (isDeclaration(line)) {
				declarationsWaitingForTheirLine.add(line);
				continue;
			}

			int lineNumber = index + 1;
			for (String declaration : declarationsWaitingForTheirLine) {
				expectedFindings.add(read(declaration, lineNumber));
			}
			declarationsWaitingForTheirLine.clear();
		}

		if (!declarationsWaitingForTheirLine.isEmpty()) {
			throw new IllegalArgumentException(
					"A declaration stands at the end of the document, with no line below it: "
							+ declarationsWaitingForTheirLine.get(0));
		}

		return List.copyOf(expectedFindings);
	}

	private static boolean isDeclaration(String line) {
		return line.strip().startsWith(DECLARATION_START);
	}

	private static ExpectedFinding read(String declaration, int lineNumber) {
		String body = declaration.strip();

		if (!body.endsWith(DECLARATION_END)) {
			throw new IllegalArgumentException("A declaration has to end with " + DECLARATION_END
					+ ", this one does not: " + declaration);
		}

		body = body.substring(DECLARATION_START.length(), body.length() - DECLARATION_END.length());

		List<String> parts = splitIntoParts(body);
		if (parts.size() != 5) {
			throw new IllegalArgumentException("A declaration names a rule, a severity, a column, the covered"
					+ " text and the message, separated by " + SEPARATOR + ", this one has " + parts.size()
					+ " parts: " + declaration);
		}

		return new ExpectedFinding(lineNumber, readColumn(parts.get(2), declaration), unescape(parts.get(3)),
				parts.get(1).toUpperCase(Locale.ROOT), ISSUE_TYPE_PREFIX + parts.get(0), unescape(parts.get(4)));
	}

	private static int readColumn(String part, String declaration) {
		try {
			return Integer.parseInt(part);
		} catch (NumberFormatException notANumber) {
			throw new IllegalArgumentException("A declaration names the column its finding starts in,"
					+ " counted from 1, this one names " + part + ": " + declaration, notANumber);
		}
	}

	/**
	 * Cuts the declaration at its separators. The message keeps every space it was reported with,
	 * except the one separating it from the vertical bar in front of it, so that a message with a
	 * double space in it can be declared.
	 */
	private static List<String> splitIntoParts(String body) {
		List<String> parts = new ArrayList<>();

		int partStart = 0;
		while (true) {
			int separator = body.indexOf(SEPARATOR, partStart);

			if (separator < 0 || parts.size() == 4) {
				parts.add(withoutOneSpaceAround(body.substring(partStart)));
				return List.copyOf(parts);
			}

			parts.add(withoutOneSpaceAround(body.substring(partStart, separator)));
			partStart = separator + SEPARATOR.length();
		}
	}

	private static String withoutOneSpaceAround(String part) {
		String withoutLeadingSpace = part.startsWith(" ") ? part.substring(1) : part;

		return withoutLeadingSpace.endsWith(" ")
				? withoutLeadingSpace.substring(0, withoutLeadingSpace.length() - 1)
				: withoutLeadingSpace;
	}

	private static String unescape(String text) {
		StringBuilder unescaped = new StringBuilder(text.length());

		for (int index = 0; index < text.length(); index++) {
			char character = text.charAt(index);

			if (character != '\\' || index + 1 >= text.length()) {
				unescaped.append(character);
				continue;
			}

			char escaped = text.charAt(++index);
			switch (escaped) {
				case 'n' -> unescaped.append('\n');
				case 't' -> unescaped.append('\t');
				case '\\' -> unescaped.append('\\');
				default -> unescaped.append('\\').append(escaped);
			}
		}

		return unescaped.toString();
	}

	/**
	 * Writes a finding as one line: where it stands, and the declaration that would state it. A
	 * declaration missing from a document can be copied out of a failed run, leaving the
	 * <code>line</code> in front of it, which the document says by where the declaration stands.
	 * 
	 * @return the finding as one line, never <code>null</code>
	 */
	String render() {
		return String.format("line %d: %s %s | %s | %d | %s | %s %s",
				this.lineNumber, DECLARATION_START, this.issueTypeId.substring(ISSUE_TYPE_PREFIX.length()),
				this.severity, this.column, escape(this.coveredText), escape(this.message), DECLARATION_END);
	}

	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
	}

}
