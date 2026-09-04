/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service;

import java.util.ArrayList;
import java.util.List;

import com.advantest.markdown.MarkdownCustomization;
import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.markdown.service.resources.ResourceResolverRegistry;
import com.advantest.resources.FileSchemeUriResolver;
import com.advantest.resources.LocalFileSystemResourceResolver;
import com.advantest.resources.Resource;
import com.advantest.resources.ResourceResolver;
import com.advantest.resources.UnresolvedResource;
import com.advantest.resources.UriResolver;
import com.advantest.markdown.service.validation.MarkdownValidation;
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
 * 
 * <p>Markdown documents refer to other documents, to images and to directories. Where those live
 * is nothing this service knows: a {@link ResourceResolver} of the surrounding environment answers
 * it, and the same resolver also created the {@link Resource} a document itself came from. Every
 * method taking Markdown source code therefore has a variant taking that resource as well; the
 * variants without it read a document of unknown origin, whose references cannot be resolved.</p>
 * 
 * @see ResourceResolver
 */
public class MarkdownService {

	private final MarkdownParserAndHtmlRenderer parserAndRenderer;

	private final MarkdownValidation validation;

	/**
	 * Creates a service using the default Markdown parser and HTML renderer configuration
	 * and resolving references in the local file system.
	 * Use {@link #builder()} if you need to customize the parser, the renderer or the resolver.
	 */
	public MarkdownService() {
		this(new MarkdownParserAndHtmlRenderer(), ResourceResolverRegistry.ofLocalFileSystem());
	}

	/**
	 * Creates a service delegating to the given Markdown parser and HTML renderer and resolving
	 * references with the given resolvers.
	 * 
	 * @param parserAndRenderer the parser and renderer to delegate to, must not be <code>null</code>
	 * @param resourceResolvers the resolvers of everything a document refers to, must not be
	 *                          <code>null</code>
	 */
	MarkdownService(MarkdownParserAndHtmlRenderer parserAndRenderer,
			ResourceResolverRegistry resourceResolvers) {
		if (parserAndRenderer == null || resourceResolvers == null) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}
		this.parserAndRenderer = parserAndRenderer;
		this.validation = new MarkdownValidation(resourceResolvers);
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
	 * <p>The parsed document does not know where it came from, hence everything it refers to stays
	 * unresolved. Use {@link #parseMarkdown(String, Resource)} whenever the origin is known.</p>
	 * 
	 * @param markdownSourceCode the Markdown source code to be parsed
	 * @return the parsed abstract syntax tree's root, never <code>null</code>
	 * @see MarkdownParserAndHtmlRenderer#parseMarkdown(String)
	 */
	public Document parseMarkdown(String markdownSourceCode) {
		return this.parserAndRenderer.parseMarkdown(markdownSourceCode);
	}

	/**
	 * Reads the given Markdown source code and parses it, i.e. creates the source code's
	 * abstract syntax tree representation, a so called {@link Document}, which remembers the
	 * resource the source code came from.
	 * 
	 * @param markdownSourceCode the Markdown source code to be parsed
	 * @param documentResource the resource the source code came from, must not be <code>null</code>,
	 *                         pass {@link UnresolvedResource#UNKNOWN_DOCUMENT} if it is unknown
	 * @return the parsed abstract syntax tree's root, never <code>null</code>
	 * @throws IllegalArgumentException if the given resource is <code>null</code>
	 * @see MarkdownParserAndHtmlRenderer#parseMarkdown(String, Resource)
	 */
	public Document parseMarkdown(String markdownSourceCode, Resource documentResource) {
		return this.parserAndRenderer.parseMarkdown(markdownSourceCode, documentResource);
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
	 * Convenience method for parsing Markdown source code that came from the given resource
	 * and then translating it to HTML.
	 * 
	 * @param markdownSourceCode the Markdown source code to be parsed and translated to HTML
	 * @param documentResource the resource the source code came from, must not be <code>null</code>,
	 *                         pass {@link UnresolvedResource#UNKNOWN_DOCUMENT} if it is unknown
	 * @return the resulting HTML source code
	 * @throws IllegalArgumentException if the given resource is <code>null</code>
	 * @see MarkdownParserAndHtmlRenderer#parseMarkdownAndRenderHtml(String, Resource)
	 */
	public String parseMarkdownAndRenderHtml(String markdownSourceCode, Resource documentResource) {
		return this.parserAndRenderer.parseMarkdownAndRenderHtml(markdownSourceCode, documentResource);
	}

	/**
	 * Checks the given parsed Markdown document and reports the problems found in it, e.g. links
	 * that cannot be resolved.
	 * 
	 * <p>Everything the document refers to is resolved relative to the resource the document came
	 * from, i.e. the one given when it was parsed. A document of unknown origin resolves nothing,
	 * and every reference of it is reported as a problem of its own.</p>
	 * 
	 * <p>The returned issues are ordered by their start offset, so that two validation runs over
	 * equal source code return equal lists.</p>
	 * 
	 * @param markdownDocument the parsed Markdown document to be checked, must not be <code>null</code>
	 * @return the problems found, ordered by start offset, empty if there are none,
	 *         never <code>null</code> and not modifiable
	 * @throws IllegalArgumentException if the given document is <code>null</code>
	 */
	public List<ValidationIssue> validateMarkdown(Document markdownDocument) {
		return this.validation.validate(markdownDocument);
	}

	/**
	 * Convenience method parsing the given Markdown source code of unknown origin and checking it.
	 * 
	 * @param markdownSourceCode the Markdown source code to be checked, must not be <code>null</code>
	 * @return the problems found, ordered by start offset, empty if there are none,
	 *         never <code>null</code> and not modifiable
	 * @throws IllegalArgumentException if the given source code is <code>null</code>
	 * @see #validateMarkdown(Document)
	 */
	public List<ValidationIssue> validateMarkdown(String markdownSourceCode) {
		if (markdownSourceCode == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		return this.validation.validate(parseMarkdown(markdownSourceCode));
	}

	/**
	 * Convenience method parsing the given Markdown source code that came from the given resource
	 * and checking it.
	 * 
	 * @param markdownSourceCode the Markdown source code to be checked, must not be <code>null</code>
	 * @param documentResource the resource the source code came from, must not be <code>null</code>,
	 *                         pass {@link UnresolvedResource#UNKNOWN_DOCUMENT} if it is unknown
	 * @return the problems found, ordered by start offset, empty if there are none,
	 *         never <code>null</code> and not modifiable
	 * @throws IllegalArgumentException if one of the arguments is <code>null</code>
	 * @see #validateMarkdown(Document)
	 */
	public List<ValidationIssue> validateMarkdown(String markdownSourceCode, Resource documentResource) {
		if (markdownSourceCode == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		return this.validation.validate(parseMarkdown(markdownSourceCode, documentResource));
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

		private ResourceResolver localFileSystemResolver = new LocalFileSystemResourceResolver();

		private final List<UriResolver> uriResolvers = new ArrayList<>();

		private Builder() {
		}

		/**
		 * Resolves everything a Markdown document refers to in the file system of the machine this
		 * code runs on, which is what happens anyway if nothing else is said. Say it to say it.
		 * 
		 * @return this builder for method chaining, never <code>null</code>
		 */
		public Builder withLocalFileSystemResourceResolver() {
			return withLocalFileSystemResourceResolver(new LocalFileSystemResourceResolver());
		}

		/**
		 * Resolves everything a Markdown document refers to with the given resolver of the local
		 * file system. There is one such resolver or there is none, so a later call replaces an
		 * earlier one.
		 * 
		 * @param resolver the resolver of the local file system, must not be <code>null</code>
		 * @return this builder for method chaining, never <code>null</code>
		 * @throws IllegalArgumentException if the given resolver is <code>null</code>
		 */
		Builder withLocalFileSystemResourceResolver(ResourceResolver resolver) {
			if (resolver == null) {
				throw new IllegalArgumentException("Argument must not be null.");
			}
			this.localFileSystemResolver = resolver;
			return this;
		}

		/**
		 * Adds a resolver for references naming a scheme, e.g. addresses of a version control
		 * system's web interface.
		 * 
		 * <p>Several resolvers may know the same scheme and tell each other apart by the host or by
		 * the beginning of the address, so the one added last that says it is
		 * {@link UriResolver#isResponsibleFor(java.net.URI) responsible} answers for a
		 * reference.</p>
		 * 
		 * @param resolver the resolver to be added, must not be <code>null</code>
		 * @return this builder for method chaining, never <code>null</code>
		 * @throws IllegalArgumentException if the given resolver is <code>null</code>
		 */
		public Builder withUriResolver(UriResolver resolver) {
			if (resolver == null) {
				throw new IllegalArgumentException("Argument must not be null.");
			}
			this.uriResolvers.add(resolver);
			return this;
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
			List<UriResolver> resolversOfAScheme = new ArrayList<>();
			resolversOfAScheme.add(new FileSchemeUriResolver(this.localFileSystemResolver));
			resolversOfAScheme.addAll(this.uriResolvers);

			ResourceResolverRegistry resolvers =
					new ResourceResolverRegistry(this.localFileSystemResolver, resolversOfAScheme);
			return new MarkdownService(this.parserAndRendererBuilder.build(), resolvers);
		}

	}

}
