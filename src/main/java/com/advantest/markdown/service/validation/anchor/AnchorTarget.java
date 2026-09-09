/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.anchor;

import com.advantest.resources.Resource;

/**
 * The place inside another document a link points to, handed to the {@link AnchorValidator}s
 * together with the place the link was found.
 * 
 * <p>What is meant inside the target is named by the fragment of the link, e.g. the
 * <code>section</code> of <code>guide.md#section</code>. It can only be looked for once the target
 * itself has been found, so a target handed here is one that exists.</p>
 * 
 * <p>Who answers for it is decided by the path as it is written in the document and not by the
 * resolved resource: what a resolved path looks like is the business of whoever resolved it, and an
 * extension cannot be read from it.</p>
 * 
 * @param targetPath the path of the link target as it is written in the document, without the
 *        fragment, must neither be <code>null</code> nor blank
 * @param anchor the fragment of the link target, without the leading <code>#</code>, must neither
 *        be <code>null</code> nor blank
 * @param targetResource the resource the path points to, must not be <code>null</code>
 * @param lineNumber the line the fragment is written in, starting at 1
 * @param startOffset the <code>#</code> of the fragment in the document, starting at 0, inclusive
 * @param endOffset the character following the fragment, exclusive, must not be smaller than the
 *        start offset
 */
public record AnchorTarget(String targetPath, String anchor, Resource targetResource,
		int lineNumber, int startOffset, int endOffset) {

	/**
	 * Creates a target, rejecting incomplete or contradictory data.
	 * 
	 * @throws IllegalArgumentException if an argument is <code>null</code>, blank or outside its
	 *         allowed range
	 */
	public AnchorTarget {
		if (targetPath == null || targetPath.isBlank()) {
			throw new IllegalArgumentException("A target path is required.");
		}
		if (anchor == null || anchor.isBlank()) {
			throw new IllegalArgumentException("An anchor is required.");
		}
		if (targetResource == null) {
			throw new IllegalArgumentException("A target resource is required.");
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
