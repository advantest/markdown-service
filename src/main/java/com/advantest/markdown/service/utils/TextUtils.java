/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.utils;

/**
 * Helpers for working with positions in a text.
 */
public final class TextUtils {

	/**
	 * Determines the number of the line the given offset points into, counting line breaks the way
	 * an Eclipse document does, i.e. <code>\n</code>, <code>\r\n</code> and <code>\r</code> all end
	 * a line.
	 * 
	 * @param text the text to count in, must not be <code>null</code>
	 * @param offset the offset to determine the line for
	 * @return the line number, starting at 1
	 */
	public static int getLineNumberForOffset(CharSequence text, int offset) {
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

	private TextUtils() {
		// utility class, not meant to be instantiated
	}

}
