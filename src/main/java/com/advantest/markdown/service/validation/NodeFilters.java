/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import java.util.List;

import com.advantest.flexmark.ext.math.MathFormulaDisplayModeNode;
import com.advantest.flexmark.ext.math.MathFormulaInLineNode;
import com.advantest.flexmark.ext.plantuml.PlantUmlBlockNode;
import com.advantest.flexmark.ext.plantuml.PlantUmlFencedCodeBlockNode;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.HtmlCommentBlock;
import com.vladsch.flexmark.ast.HtmlInline;
import com.vladsch.flexmark.ast.HtmlInlineComment;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.util.ast.Node;

/**
 * The node filters a Markdown validator usually needs.
 * 
 * <p>Markdown embeds other languages, and a rule about Markdown code is not meant to be applied
 * to them: a link written in a code span is an example, not a link, and one written in a PlantUML
 * diagram belongs to the diagram. Each constant here hides one such embedded language.</p>
 * 
 * <p>{@link #MARKDOWN_CODE} combines all of them and is what a Markdown rule uses. Note that it
 * hides HTML <em>tags</em> only: Markdown code between two tags, e.g. the link in
 * <code>&lt;em&gt;[label](some/file.md)&lt;/em&gt;</code>, is parsed as Markdown and stays
 * visible, while everything inside an HTML block does not.</p>
 */
public final class NodeFilters {

	/** Ignores nothing, i.e. offers every node of the document to the validator. */
	public static final NodeFilter NOTHING = node -> false;

	/** Ignores code spans, fenced code blocks and indented code blocks. */
	public static final NodeFilter VERBATIM =
			ofTypes(Code.class, FencedCodeBlock.class, IndentedCodeBlock.class);

	/** Ignores HTML comments, both the block and the inline form. */
	public static final NodeFilter COMMENTS = ofTypes(HtmlCommentBlock.class, HtmlInlineComment.class);

	/** Ignores HTML blocks and HTML tags written inline, and with them the HTML comments. */
	public static final NodeFilter HTML = ofTypes(HtmlBlock.class, HtmlInline.class);

	/** Ignores PlantUML diagrams, both the fenced and the bare <code>@startuml</code> form. */
	public static final NodeFilter PLANTUML =
			ofTypes(PlantUmlBlockNode.class, PlantUmlFencedCodeBlockNode.class);

	/** Ignores mathematical formulas, both the inline and the display mode form. */
	public static final NodeFilter MATH =
			ofTypes(MathFormulaInLineNode.class, MathFormulaDisplayModeNode.class);

	/**
	 * Ignores everything that is not Markdown code: verbatim text, comments, HTML tags,
	 * PlantUML diagrams and mathematical formulas. This is what a rule about Markdown code needs
	 * and therefore the default of {@link MarkdownValidator#getIgnoredNodes()}.
	 */
	public static final NodeFilter MARKDOWN_CODE = VERBATIM.or(COMMENTS).or(HTML).or(PLANTUML).or(MATH);

	private NodeFilters() {
		// utility class, not to be instantiated
	}

	/**
	 * Creates a filter ignoring the nodes of the given types and their subtypes.
	 * 
	 * @param nodeTypes the node types to be ignored, must not be <code>null</code> and must not be empty
	 * @return the filter, never <code>null</code>
	 */
	@SafeVarargs
	public static NodeFilter ofTypes(Class<? extends Node>... nodeTypes) {
		if (nodeTypes == null || nodeTypes.length == 0) {
			throw new IllegalArgumentException("Argument must not be null or empty.");
		}

		List<Class<? extends Node>> types = List.of(nodeTypes);
		return node -> types.stream().anyMatch(type -> type.isInstance(node));
	}

}
