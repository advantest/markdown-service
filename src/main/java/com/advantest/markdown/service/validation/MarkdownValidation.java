/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.markdown.service.resources.ResourceResolverRegistry;
import com.advantest.markdown.service.validation.anchor.AnchorValidator;
import com.advantest.markdown.service.validation.uri.UriValidator;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;

/**
 * Applies the validation rules to a parsed Markdown document.
 * 
 * <p>The rules themselves live in {@link MarkdownValidator}s this one is composed of, each
 * covering one kind of Markdown construct. This class walks the document once and offers every
 * node to the validators triggered by it, leaving out what a validator
 * {@link MarkdownValidator#getIgnoredNodes() ignores}.</p>
 */
public class MarkdownValidation {

	private final MarkdownParserAndHtmlRenderer parserAndRenderer;

	private final List<MarkdownValidator> validators;

	private final List<NodeFilter> distinctFilters;

	private final Map<Class<?>, List<MarkdownValidator>> validatorsByNodeType = new ConcurrentHashMap<>();

	/**
	 * Creates a validation applying the built-in validators, resolving everything a document
	 * refers to with the given resolvers.
	 * 
	 * @param parserAndRenderer the parser reading a document a link points into, must not be
	 *                          <code>null</code>
	 * @param resourceResolvers the resolvers of everything a document refers to, must not be
	 *                          <code>null</code>
	 */
	public MarkdownValidation(MarkdownParserAndHtmlRenderer parserAndRenderer,
			ResourceResolverRegistry resourceResolvers) {
		this(parserAndRenderer, resourceResolvers, List.of(), List.of());
	}

	/**
	 * Creates a validation applying the built-in validators, resolving everything a document
	 * refers to with the given resolvers, handing a target naming a scheme to the given URI
	 * validators and a fragment of a target to the given anchor validators.
	 * 
	 * @param parserAndRenderer the parser reading a document a link points into, must not be
	 *                          <code>null</code>
	 * @param resourceResolvers the resolvers of everything a document refers to, must not be
	 *                          <code>null</code>
	 * @param uriValidators the validators of a target naming a scheme, the first one saying it is
	 *                      responsible answers for a target, must not be <code>null</code>
	 * @param anchorValidators the validators of what a link names inside its target, the first one
	 *                         saying it is responsible answers for a target, must not be
	 *                         <code>null</code>
	 */
	public MarkdownValidation(MarkdownParserAndHtmlRenderer parserAndRenderer,
			ResourceResolverRegistry resourceResolvers,
			List<UriValidator> uriValidators,
			List<AnchorValidator> anchorValidators) {
		this(parserAndRenderer,
				List.of(new MarkdownLinkValidator(resourceResolvers, uriValidators, anchorValidators),
						new MarkdownAnchorValidator()));
	}

	/**
	 * Creates a validation applying the given validators.
	 * 
	 * @param parserAndRenderer the parser reading a document a link points into, must not be
	 *                          <code>null</code>
	 * @param validators the validators to be applied, must not be <code>null</code>
	 */
	MarkdownValidation(MarkdownParserAndHtmlRenderer parserAndRenderer,
			List<MarkdownValidator> validators) {
		if (parserAndRenderer == null || validators == null) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}

		this.parserAndRenderer = parserAndRenderer;

		this.validators = List.copyOf(validators);

		List<NodeFilter> filters = new ArrayList<>(this.validators.size());
		for (MarkdownValidator validator : this.validators) {
			NodeFilter filter = validator.getIgnoredNodes();
			if (filter == null) {
				throw new IllegalArgumentException(
						"Validator " + validator.getClass().getName() + " has no node filter.");
			}
			if (filters.stream().noneMatch(knownFilter -> knownFilter == filter)) {
				filters.add(filter);
			}
		}
		this.distinctFilters = List.copyOf(filters);
	}

	/**
	 * Checks the given Markdown document.
	 * 
	 * @param document the parsed Markdown document to be checked, must not be <code>null</code>
	 * @return the problems found, ordered by start offset, never <code>null</code> and not modifiable
	 */
	public List<ValidationIssue> validate(Document document) {
		if (document == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}

		List<ValidationIssue> issues = new ArrayList<>();
		MarkdownValidationContext context = MarkdownValidationContext.parsingWith(this.parserAndRenderer);

		visit(document, Collections.newSetFromMap(new IdentityHashMap<>()), context, issues);

		issues.sort(Comparator.comparingInt(ValidationIssue::startOffset));
		return List.copyOf(issues);
	}

	private void visit(Node node, Set<NodeFilter> ignoringFilters, MarkdownValidationContext context,
			List<ValidationIssue> issues) {
		List<NodeFilter> filtersStartingToIgnoreHere = null;
		for (NodeFilter filter : this.distinctFilters) {
			if (!ignoringFilters.contains(filter) && filter.isIgnored(node)) {
				if (filtersStartingToIgnoreHere == null) {
					filtersStartingToIgnoreHere = new ArrayList<>(2);
				}
				filtersStartingToIgnoreHere.add(filter);
				ignoringFilters.add(filter);
			}
		}

		for (MarkdownValidator validator : validatorsFor(node.getClass())) {
			if (!ignoringFilters.contains(validator.getIgnoredNodes()) && validator.isValidatorFor(node)) {
				issues.addAll(validator.validate(node, context));
			}
		}

		for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
			visit(child, ignoringFilters, context, issues);
		}

		if (filtersStartingToIgnoreHere != null) {
			filtersStartingToIgnoreHere.forEach(ignoringFilters::remove);
		}
	}

	private List<MarkdownValidator> validatorsFor(Class<?> nodeType) {
		return this.validatorsByNodeType.computeIfAbsent(nodeType, type -> this.validators.stream()
				.filter(validator -> validator.getTriggeringNodeTypes().stream()
						.anyMatch(triggeringType -> triggeringType.isAssignableFrom(type)))
				.toList());
	}

}