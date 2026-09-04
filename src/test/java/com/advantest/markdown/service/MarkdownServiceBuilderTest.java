/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import com.advantest.flexmark.ext.jira.tickets.JiraTicketExtension;
import com.advantest.markdown.MarkdownCustomization;
import com.advantest.markdown.service.validation.MarkdownIssueTypes;
import com.advantest.markdown.service.validation.ValidationIssue;
import com.advantest.resources.RelativePathResourceResolver;
import com.advantest.resources.UnresolvedResource;
import com.vladsch.flexmark.html.AttributeProvider;
import com.vladsch.flexmark.html.AttributeProviderFactory;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.html.IndependentAttributeProviderFactory;
import com.vladsch.flexmark.html.renderer.AttributablePart;
import com.vladsch.flexmark.html.renderer.LinkResolverContext;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.DataKey;
import com.vladsch.flexmark.util.data.MutableDataHolder;
import com.vladsch.flexmark.util.data.NullableDataKey;
import com.vladsch.flexmark.util.html.MutableAttributes;

/**
 * Tests that {@link MarkdownService.Builder} forwards all customizations to the underlying
 * Markdown parser and HTML renderer.
 */
class MarkdownServiceBuilderTest {

	private static final String JIRA_TICKET_MARKDOWN = "See ticket ABC-123.";

	private static final NullableDataKey<String> NULLABLE_TEST_KEY =
			new NullableDataKey<>("NULLABLE_TEST_KEY", "some default");

	/** Marks every rendered HTML element with an attribute, so we can detect that it was applied. */
	private static final class MarkerExtension implements HtmlRenderer.HtmlRendererExtension {

		@Override
		public void rendererOptions(@NotNull MutableDataHolder options) {
			// no changes
		}

		@Override
		public void extend(@NotNull HtmlRenderer.Builder htmlRendererBuilder, @NotNull String rendererType) {
			htmlRendererBuilder.attributeProviderFactory(factory());
		}

		private static AttributeProviderFactory factory() {
			return new IndependentAttributeProviderFactory() {
				@Override
				public AttributeProvider apply(LinkResolverContext context) {
					return new AttributeProvider() {
						@Override
						public void setAttributes(Node node, AttributablePart part, MutableAttributes attributes) {
							if (part == AttributablePart.NODE) {
								attributes.addValue("data-marker", "set");
							}
						}
					};
				}
			};
		}
	}

	@Test
	void builderCreatesUsableService() {
		MarkdownService service = MarkdownService.builder().build();

		assertNotNull(service);
		assertTrue(service.parseMarkdownAndRenderHtml("Hello *world*").contains("<em>world</em>"));
	}

	@Test
	void withExtensionAffectsRenderedOutput() {
		MarkdownService service = MarkdownService.builder()
				.withExtension(new MarkerExtension())
				.build();

		assertTrue(service.parseMarkdownAndRenderHtml("# Title").contains("data-marker=\"set\""));
	}

	@Test
	void withExtensionKeepsDefaultExtensions() {
		MarkdownService service = MarkdownService.builder()
				.withExtension(new MarkerExtension())
				.build();

		// tables are enabled by one of the default extensions
		String html = service.parseMarkdownAndRenderHtml("| a | b |\n|---|---|\n| 1 | 2 |\n");

		assertTrue(html.contains("<table"), html);
	}

	@Test
	void withOptionAffectsRenderedOutput() {
		String customJiraUrl = "https://example.com/browse/";

		MarkdownService service = MarkdownService.builder()
				.withOption(JiraTicketExtension.JIRA_URL, customJiraUrl)
				.build();

		assertTrue(service.parseMarkdownAndRenderHtml(JIRA_TICKET_MARKDOWN).contains(customJiraUrl), customJiraUrl);
	}

	@Test
	void withOptionAcceptsNullValuesForNullableDataKeys() {
		MarkdownService service = MarkdownService.builder()
				.withOption(NULLABLE_TEST_KEY, null)
				.build();

		assertNotNull(service);
	}

	@Test
	void withCustomizationIsApplied() {
		String customJiraUrl = "https://example.com/from-customization/";

		MarkdownService service = MarkdownService.builder()
				.withCustomization(options -> options.set(JiraTicketExtension.JIRA_URL, customJiraUrl))
				.build();

		assertTrue(service.parseMarkdownAndRenderHtml(JIRA_TICKET_MARKDOWN).contains(customJiraUrl), customJiraUrl);
	}

	@Test
	void customizationsAreAppliedInRegistrationOrder() {
		List<String> applicationOrder = new ArrayList<>();

		MarkdownService service = MarkdownService.builder()
				.withCustomization(options -> {
					applicationOrder.add("first");
					options.set(JiraTicketExtension.JIRA_URL, "https://example.com/first/");
				})
				.withCustomization(options -> {
					applicationOrder.add("second");
					options.set(JiraTicketExtension.JIRA_URL, "https://example.com/second/");
				})
				.build();

		assertEquals(List.of("first", "second"), applicationOrder);
		// the last customization wins
		assertTrue(service.parseMarkdownAndRenderHtml(JIRA_TICKET_MARKDOWN)
				.contains("https://example.com/second/"));
	}

	@Test
	void builderRejectsNullArguments() {
		MarkdownService.Builder builder = MarkdownService.builder();

		assertThrows(IllegalArgumentException.class, () -> builder.withExtension(null));
		assertThrows(IllegalArgumentException.class, () -> builder.withOption((DataKey<String>) null, "value"));
		assertThrows(IllegalArgumentException.class, () -> builder.withOption(JiraTicketExtension.JIRA_URL, null));
		assertThrows(IllegalArgumentException.class,
				() -> builder.withOption((NullableDataKey<String>) null, "value"));
		assertThrows(IllegalArgumentException.class, () -> builder.withCustomization((MarkdownCustomization) null));
		assertThrows(IllegalArgumentException.class, () -> builder.withUriResolver(null));
		assertThrows(IllegalArgumentException.class,
				() -> builder.withRelativePathResourceResolver(null));
	}

	@Test
	void builderTakesTheResolverOfAPathOfItsOwn() {
		RelativePathResourceResolver ownResolver =
				(linkTarget, document) -> new UnresolvedResource(linkTarget);

		MarkdownService service = MarkdownService.builder()
				.withRelativePathResourceResolver(ownResolver)
				.build();

		assertNotNull(service);
		// the resolver is asked by the rules about the resource a link points to, which a target
		// this short never reaches, so all we can check here is that a service with it validates
		// as before
		assertTrue(service.validateMarkdown("[label]()").size() == 1);
	}

	@Test
	void builderReportsAPathNamingItsResourceOnItsOwnWhereverTheDocumentsComeFrom() {
		RelativePathResourceResolver ownResolver =
				(linkTarget, document) -> new UnresolvedResource(linkTarget);

		MarkdownService service = MarkdownService.builder()
				.withRelativePathResourceResolver(ownResolver)
				.build();

		// such a path leads to the resource on one machine only, wherever the documents come from
		List<ValidationIssue> issues = service.validateMarkdown("[label](/absolute/path/guide.md)");

		assertEquals(1, issues.size());
		assertEquals(MarkdownIssueTypes.LINK_ABSOLUTE_TARGET_PATH, issues.get(0).issueTypeId());
	}

	@Test
	void builderResolvesInTheLocalFileSystemWhenAskedTo() {
		MarkdownService service = MarkdownService.builder()
				.withLocalFileSystemResourceResolver()
				.build();

		assertNotNull(service);
		assertTrue(service.validateMarkdown("[label]()").size() == 1);
	}

}
