/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service;

import java.io.File;
import java.io.IOException;
import java.util.List;

import com.advantest.markdown.MarkdownCustomization;
import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.markdown.service.validation.ValidationIssue;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.DataKey;
import com.vladsch.flexmark.util.data.NullableDataKey;
import com.vladsch.flexmark.util.misc.Extension;

/**
 * Facade offering Markdown parsing, HTML rendering and validation features.
 * 
 * <p>Use the parameter-less constructor to get a service with the default configuration.
 * If the underlying Markdown parser and HTML renderer needs to be customized, e.g. by adding
 * further flexmark extensions or by setting flexmark options, use the {@link #builder()}:</p>
 * 
 * <pre>
 * MarkdownService service = MarkdownService.builder()
 *         .withExtension(SomeFlexmarkExtension.create())
 *         .withOption(SomeExtension.SOME_OPTION, "some value")
 *         .build();
 * </pre>
 */
public class MarkdownService {

	private final MarkdownParserAndHtmlRenderer parserAndRenderer;

	private final MarkdownValidator validator = new MarkdownValidator();

	/**
	 * Creates a service using the default Markdown parser and HTML renderer configuration.
	 * Use {@link #builder()} if you need to customize the parser and renderer.
	 */
	public MarkdownService() {
		this(new MarkdownParserAndHtmlRenderer());
	}

	/**
	 * Creates a service delegating to the given Markdown parser and HTML renderer.
	 * 
	 * @param parserAndRenderer the parser and renderer to delegate to, must not be <code>null</code>
	 */
	MarkdownService(MarkdownParserAndHtmlRenderer parserAndRenderer) {
		if (parserAndRenderer == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		this.parserAndRenderer = parserAndRenderer;
	}

	/**
	 * Creates a builder for a service with a customized Markdown parser and HTML renderer.
	 * 
	 * @return a new builder, never <code>null</code>
	 */
	public static Builder builder() {
		return new Builder();
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

	/**
	 * Checks the given Markdown source code and reports the problems found in it, e.g. links
	 * that cannot be resolved.
	 * 
	 * <p>The source code is validated as it is, without access to a file system: only problems
	 * that are visible within the given text are found. Checks that need the document's
	 * surroundings, e.g. whether a linked file exists, are not performed here.</p>
	 * 
	 * <p>The returned issues are ordered by their start offset, so that two validation runs over
	 * equal source code return equal lists.</p>
	 * 
	 * @param markdownSourceCode the Markdown source code to be checked, must not be <code>null</code>
	 * @return the problems found, ordered by start offset, empty if there are none,
	 *         never <code>null</code> and not modifiable
	 */
	public List<ValidationIssue> validateMarkdown(String markdownSourceCode) {
		if (markdownSourceCode == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		return this.validator.validate(markdownSourceCode);
	}

	/**
	 * Builder for a {@link MarkdownService} with a customized Markdown parser and HTML renderer.
	 * 
	 * <p>All customizations are applied in the order in which they are registered here,
	 * i.e. later customizations override earlier ones. They are applied on top of the default
	 * configuration, so they also override the defaults.</p>
	 * 
	 * @see MarkdownParserAndHtmlRenderer.Builder
	 */
	public static final class Builder {

		private final MarkdownParserAndHtmlRenderer.Builder parserAndRendererBuilder =
				MarkdownParserAndHtmlRenderer.builder();

		private Builder() {
		}

		/**
		 * Adds the given flexmark extension to the extensions already configured,
		 * i.e. it does not replace or remove any of the default extensions.
		 * 
		 * @param extension the flexmark extension to be added, must not be <code>null</code>
		 * @return this builder for method chaining, never <code>null</code>
		 * @see MarkdownParserAndHtmlRenderer.Builder#withExtension(Extension)
		 */
		public Builder withExtension(Extension extension) {
			this.parserAndRendererBuilder.withExtension(extension);
			return this;
		}

		/**
		 * Sets the given flexmark option value.
		 * 
		 * @param <T> the option value's type
		 * @param key the flexmark data key of the option to be set, must not be <code>null</code>
		 * @param value the option's value, must not be <code>null</code>
		 * @return this builder for method chaining, never <code>null</code>
		 * @see MarkdownParserAndHtmlRenderer.Builder#withOption(DataKey, Object)
		 */
		public <T> Builder withOption(DataKey<T> key, T value) {
			this.parserAndRendererBuilder.withOption(key, value);
			return this;
		}

		/**
		 * Sets the given flexmark option value which may be <code>null</code>.
		 * 
		 * @param <T> the option value's type
		 * @param key the flexmark data key of the option to be set, must not be <code>null</code>
		 * @param value the option's value, may be <code>null</code>
		 * @return this builder for method chaining, never <code>null</code>
		 * @see MarkdownParserAndHtmlRenderer.Builder#withOption(NullableDataKey, Object)
		 */
		public <T> Builder withOption(NullableDataKey<T> key, T value) {
			this.parserAndRendererBuilder.withOption(key, value);
			return this;
		}

		/**
		 * Adds the given customization, i.e. arbitrary changes to the parser's and renderer's options.
		 * This is the generic extension point for customizations contributed by other components,
		 * e.g. Eclipse plug-in extensions or dependency injection beans.
		 * 
		 * @param customization the customization to be applied, must not be <code>null</code>
		 * @return this builder for method chaining, never <code>null</code>
		 * @see MarkdownParserAndHtmlRenderer.Builder#withCustomization(MarkdownCustomization)
		 */
		public Builder withCustomization(MarkdownCustomization customization) {
			this.parserAndRendererBuilder.withCustomization(customization);
			return this;
		}

		/**
		 * Creates the service with the customized Markdown parser and HTML renderer.
		 * 
		 * @return the newly created service, never <code>null</code>
		 */
		public MarkdownService build() {
			return new MarkdownService(this.parserAndRendererBuilder.build());
		}

	}

}
