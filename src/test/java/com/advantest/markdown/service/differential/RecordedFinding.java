/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.differential;

/**
 * One finding of a recorded validation run, as one row of the recorded findings file.
 * 
 * @param file the validated file, as a path relative to the corpus root, using forward slashes
 * @param lineNumber the line the finding was reported on, starting at 1
 * @param startOffset the first character of the marked text range, or <code>-1</code> if the
 *        recorded run did not report one
 * @param endOffset the character following the marked text range, or <code>-1</code> if the
 *        recorded run did not report one
 * @param severity the reported severity, e.g. <code>ERROR</code>
 * @param bundle the Eclipse bundle whose validator reported the finding
 * @param issueType the marker type the finding was reported as, the same for every Markdown finding
 * @param message the reported message, with the whitespace escapes of the recording resolved
 */
record RecordedFinding(
		String file,
		int lineNumber,
		int startOffset,
		int endOffset,
		String severity,
		String bundle,
		String issueType,
		String message) {

	private static final String COLUMN_SEPARATOR = "\t";
	private static final String NOT_RECORDED = "?";
	private static final int COLUMN_COUNT = 8;

	static RecordedFinding parse(String row) {
		String[] columns = row.split(COLUMN_SEPARATOR, -1);
		if (columns.length != COLUMN_COUNT) {
			throw new IllegalArgumentException(
					"Expected " + COLUMN_COUNT + " columns, but found " + columns.length + " in row: " + row);
		}

		return new RecordedFinding(columns[0], toNumber(columns[1]), toNumber(columns[2]), toNumber(columns[3]),
				columns[4], columns[5], columns[6], unescapeWhitespace(columns[7]));
	}

	private static int toNumber(String column) {
		return NOT_RECORDED.equals(column) ? -1 : Integer.parseInt(column);
	}

	/**
	 * Restores the message the validator produced. The recording escapes the characters that would
	 * otherwise end the row or the column, so that a message stays comparable character by
	 * character.
	 */
	private static String unescapeWhitespace(String message) {
		StringBuilder restored = new StringBuilder(message.length());

		for (int index = 0; index < message.length(); index++) {
			char character = message.charAt(index);
			if (character != '\\' || index + 1 >= message.length()) {
				restored.append(character);
				continue;
			}

			char escaped = message.charAt(++index);
			switch (escaped) {
				case 't' -> restored.append('\t');
				case 'r' -> restored.append('\r');
				case 'n' -> restored.append('\n');
				case '\\' -> restored.append('\\');
				default -> restored.append(character).append(escaped);
			}
		}

		return restored.toString();
	}

}
