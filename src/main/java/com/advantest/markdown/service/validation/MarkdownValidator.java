/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import java.util.List;
import java.util.Set;

import com.vladsch.flexmark.util.ast.Node;

/**
 * A rule checking one kind of Markdown construct.
 * 
 * <p>A validator does not search the document. It names the node types it is triggered by, and
 * {@link MarkdownValidation} hands it every node of those types the document contains, one at a
 * time. Nodes below a node its {@link #getIgnoredNodes() node filter} rejects are not offered.</p>
 * 
 * <p>A validator checking the whole document at once, e.g. one comparing all link reference
 * definitions with each other, is triggered by {@link com.vladsch.flexmark.util.ast.Document} and
 * is called once. A validator looking for something the parser does not model as a node of its
 * own, e.g. a malformed link, is triggered by {@link com.vladsch.flexmark.ast.Text} or
 * {@link com.vladsch.flexmark.ast.Paragraph} and searches the text it is given.</p>
 * 
 * <p>Implementations are stateless and are used for more than one document. Something worth
 * remembering for the duration of one validation run belongs to the {@link MarkdownValidationContext}
 * every check is handed, which is created for one run and dropped when it ends.</p>
 */
public interface MarkdownValidator {

	/**
	 * Tells which node types this validator is triggered by. Subtypes of the returned types
	 * trigger it as well.
	 * 
	 * @return the node types, never <code>null</code> and never empty
	 */
	Set<Class<? extends Node>> getTriggeringNodeTypes();

	/**
	 * Tells whether this validator checks the given node, which is of one of the
	 * {@link #getTriggeringNodeTypes() triggering node types}.
	 * 
	 * <p>Implement this only to sort out nodes the node type alone cannot distinguish, e.g. a
	 * heading that carries an anchor identifier. The default accepts all of them.</p>
	 * 
	 * @param node the node to be decided about, never <code>null</code>
	 * @return <code>true</code> if {@link #validate(Node, MarkdownValidationContext)} is to be called for
	 *         that node
	 */
	default boolean isValidatorFor(Node node) {
		return true;
	}

	/**
	 * Tells which nodes this validator does not look into.
	 * 
	 * <p>The default hides everything that is not Markdown code. A validator checking one of the
	 * embedded languages, e.g. the links in comments, returns another filter.</p>
	 * 
	 * @return the node filter, never <code>null</code>
	 */
	default NodeFilter getIgnoredNodes() {
		return NodeFilters.MARKDOWN_CODE;
	}

	/**
	 * Checks the given node.
	 * 
	 * @param node the node to be checked, never <code>null</code>
	 * @param context what this run knows besides the document, never <code>null</code>
	 * @return the problems found, {@link List#of() empty} if there are none, never <code>null</code>
	 */
	List<ValidationIssue> validate(Node node, MarkdownValidationContext context);

}
