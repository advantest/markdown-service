/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import com.vladsch.flexmark.util.ast.Node;

/**
 * Decides which parts of a Markdown document a validator does not look into.
 * 
 * <p>A node this filter rejects is skipped together with everything below it, so a filter
 * rejecting fenced code blocks hides the whole content of every fenced code block, not just
 * the block node itself.</p>
 * 
 * <p>Use the filters offered by {@link NodeFilters} instead of writing one, unless a validator
 * really needs its own.</p>
 */
@FunctionalInterface
public interface NodeFilter {

	/**
	 * Tells whether the given node and everything below it is to be skipped.
	 * 
	 * @param node the node to be decided about, never <code>null</code>
	 * @return <code>true</code> if the node and its whole subtree are to be skipped
	 */
	boolean isIgnored(Node node);

	/**
	 * Combines this filter with the given one, ignoring what either of them ignores.
	 * 
	 * @param otherFilter the filter to be combined with this one, must not be <code>null</code>
	 * @return the combined filter, never <code>null</code>
	 */
	default NodeFilter or(NodeFilter otherFilter) {
		if (otherFilter == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		return node -> isIgnored(node) || otherFilter.isIgnored(node);
	}

}
