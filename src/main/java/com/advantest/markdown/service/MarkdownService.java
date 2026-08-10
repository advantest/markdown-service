/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service;

import java.io.File;
import java.io.IOException;

import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;

/**
 * Facade offering Markdown parsing and HTML rendering features.
 */
public class MarkdownService {

	private final MarkdownParserAndHtmlRenderer parserAndRenderer;

	public MarkdownService() {
		this(new MarkdownParserAndHtmlRenderer());
	}

	public MarkdownService(MarkdownParserAndHtmlRenderer parserAndRenderer) {
		if (parserAndRenderer == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		this.parserAndRenderer = parserAndRenderer;
	}

	/**
	 * Reads the given Markdown source code and parses it, i.e. creates the source code's
	 * abstract syntax tree representation, a so called {@link Document}.
	 * 
	 * @param markdownSourceCode the Markdown source code to be parsed
	 * @return the parsed abstract syntax tree's root, never <code>null</code>
	 * @see MarkdownParserAndHtmlRenderer#parseMarkdown(String)
	 */
	public Document parseMarkdown(String markdownSourceCode) {
		return this.parserAndRenderer.parseMarkdown(markdownSourceCode);
	}

	/**
	 * Reads the given Markdown file and parses it, i.e. creates the source code's
	 * abstract syntax tree representation, a so called {@link Document}.
	 * 
	 * @param markdownFile file to be parsed, must have file extension .md
	 * @return the parsed abstract syntax tree, never <code>null</code>
	 * @throws IOException if reading the file fails
	 * @throws IllegalArgumentException if the given file is not a readable Markdown file with file extension .md
	 * @see MarkdownParserAndHtmlRenderer#parseMarkdown(File)
	 */
	public Document parseMarkdown(File markdownFile) throws IOException {
		return this.parserAndRenderer.parseMarkdown(markdownFile);
	}

	/**
	 * Translates the given abstract syntax tree (with the given {@link Node} as root) to HTML source code.
	 * 
	 * @param markdownAstNode the root of the abstract syntax tree to be translated to HTML code
	 * @return the resulting HTML source code
	 * @see MarkdownParserAndHtmlRenderer#renderHtml(Node)
	 */
	public String renderHtml(Node markdownAstNode) {
		return this.parserAndRenderer.renderHtml(markdownAstNode);
	}

	/**
	 * Convenience method for parsing Markdown source code and then translating it to HTML.
	 * 
	 * @param markdownSourceCode the Markdown source code to be parsed and translated to HTML
	 * @return the resulting HTML source code
	 * @see MarkdownParserAndHtmlRenderer#parseMarkdownAndRenderHtml(String)
	 */
	public String parseMarkdownAndRenderHtml(String markdownSourceCode) {
		return this.parserAndRenderer.parseMarkdownAndRenderHtml(markdownSourceCode);
	}

}
