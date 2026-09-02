/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.utils;

import com.vladsch.flexmark.util.ast.Node;

/**
 * Helpers for working with positions in a text.
 */
public final class TextUtils {

	/**
	 * Determines the number of the line the given offset points into. The offset is an offset in the
	 * parsed document, which is what every node reports, so the node given here only says which
	 * document is meant and may be any node of it.
	 * <p>
	 * The line is not counted here. The parser knows where every line of the document starts, and it
	 * ends a line at <code>\n</code>, <code>\r\n</code> and <code>\r</code> alike, which is what an
	 * Eclipse document does as well.
	 * 
	 * @param nodeOfTheDocument any node of the document the offset points into, must not be
	 *        <code>null</code>
	 * @param documentOffset the offset in that document to determine the line for
	 * @return the line number, starting at 1
	 */
	public static int getLineNumberForOffset(Node nodeOfTheDocument, int documentOffset) {
		int lastOffsetToLookAt = Math.max(0,
				Math.min(documentOffset, nodeOfTheDocument.getDocument().getChars().length()));

		return nodeOfTheDocument.getDocument().getLineNumber(lastOffsetToLookAt) + 1;
	}

	private TextUtils() {
		// utility class, not meant to be instantiated
	}

}
