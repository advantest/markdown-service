/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.anchor;

import com.advantest.resources.Resource;
import com.vladsch.flexmark.util.ast.Document;

/**
 * The place inside a document a link points to, handed to the {@link AnchorValidator}s together
 * with the place the link was found.
 * 
 * <p>What is meant inside the target is named by the fragment of the link, e.g. the
 * <code>section</code> of <code>guide.md#section</code>. It can only be looked for once the target
 * itself has been found, so a target handed here is one that exists.</p>
 * 
 * <p>Who answers for it is decided by the path as it is written in the document and not by the
 * resolved resource: what a resolved path looks like is the business of whoever resolved it, and an
 * extension cannot be read from it.</p>
 * 
 * <p>A link naming nothing but a fragment, e.g. <code>#section</code>, points into the document it
 * stands in. Such a target carries neither a path nor a resource, and what it names is looked for in
 * {@link #documentContainingTheLink()}, which is the document as it is being checked: reading it
 * from where it is stored would answer about a text nobody is looking at while its author is still
 * typing.</p>
 * 
 * @param targetPath the path of the link target as it is written in the document, without the
 *        fragment, <code>null</code> where the link points into the document it stands in, and
 *        never blank otherwise
 * @param anchor the fragment of the link target, without the leading <code>#</code>, must neither
 *        be <code>null</code> nor blank
 * @param targetResource the resource the path points to, <code>null</code> where the link points
 *        into the document it stands in
 * @param documentContainingTheLink the parsed document the link was found in, must not be
 *        <code>null</code>
 * @param lineNumber the line the fragment is written in, starting at 1
 * @param startOffset the <code>#</code> of the fragment in the document, starting at 0, inclusive
 * @param endOffset the character following the fragment, exclusive, must not be smaller than the
 *        start offset
 */
public record AnchorTarget(String targetPath, String anchor, Resource targetResource,
		Document documentContainingTheLink, int lineNumber, int startOffset, int endOffset) {

	/**
	 * Creates a target, rejecting incomplete or contradictory data.
	 * 
	 * @throws IllegalArgumentException if an argument is <code>null</code>, blank or outside its
	 *         allowed range, or if a path is given without a resource or a resource without a path
	 */
	public AnchorTarget {
		if (targetPath != null && targetPath.isBlank()) {
			throw new IllegalArgumentException("A target path must not be blank.");
		}
		if ((targetPath == null) != (targetResource == null)) {
			throw new IllegalArgumentException(
					"A target path and the resource it points to are given together or not at all.");
		}
		if (anchor == null || anchor.isBlank()) {
			throw new IllegalArgumentException("An anchor is required.");
		}
		if (documentContainingTheLink == null) {
			throw new IllegalArgumentException("The document containing the link is required.");
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

	/**
	 * Creates a target naming a place in the document the link stands in.
	 * 
	 * @param anchor the fragment of the link target, without the leading <code>#</code>, must
	 *        neither be <code>null</code> nor blank
	 * @param documentContainingTheLink the parsed document the link was found in, must not be
	 *        <code>null</code>
	 * @param lineNumber the line the fragment is written in, starting at 1
	 * @param startOffset the <code>#</code> of the fragment in the document, starting at 0, inclusive
	 * @param endOffset the character following the fragment, exclusive
	 * @return the target, never <code>null</code>
	 * @throws IllegalArgumentException if an argument is <code>null</code>, blank or outside its
	 *         allowed range
	 */
	public static AnchorTarget inTheDocumentItself(String anchor, Document documentContainingTheLink,
			int lineNumber, int startOffset, int endOffset) {

		return new AnchorTarget(null, anchor, null, documentContainingTheLink, lineNumber, startOffset,
				endOffset);
	}

	/**
	 * Tells whether the link names a place in the document it stands in rather than in another one.
	 * 
	 * @return <code>true</code> where the link carries a fragment and no path
	 */
	public boolean namesTheDocumentItself() {
		return this.targetPath == null;
	}

}
