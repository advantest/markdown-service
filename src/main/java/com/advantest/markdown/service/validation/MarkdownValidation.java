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

import com.advantest.resources.ResourceResolver;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;

/**
 * Applies the validation rules to a parsed Markdown document.
 * 
 * <p>The rules reproduce those of the FluentMark Eclipse plug-ins, including their messages and
 * the text ranges they mark, so that both report the same problems for the same document.</p>
 * 
 * <p>The rules themselves live in {@link MarkdownValidator}s this one is composed of, each
 * covering one kind of Markdown construct. This class walks the document once and offers every
 * node to the validators triggered by it, leaving out what a validator
 * {@link MarkdownValidator#getIgnoredNodes() ignores}.</p>
 */
public class MarkdownValidation {

	private final List<MarkdownValidator> validators;

	private final List<NodeFilter> distinctFilters;

	private final Map<Class<?>, List<MarkdownValidator>> validatorsByNodeType = new ConcurrentHashMap<>();

	/**
	 * Creates a validation applying the built-in validators, resolving everything a document
	 * refers to with the given resolver.
	 * 
	 * @param resourceResolver the resolver of everything a document refers to, must not be
	 *                         <code>null</code>
	 */
	public MarkdownValidation(ResourceResolver resourceResolver) {
		this(List.of(new MarkdownLinkValidator(resourceResolver), new MarkdownAnchorValidator()));
	}

	/**
	 * Creates a validation applying the given validators.
	 * 
	 * @param validators the validators to be applied, must not be <code>null</code>
	 */
	MarkdownValidation(List<MarkdownValidator> validators) {
		if (validators == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}

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

		visit(document, Collections.newSetFromMap(new IdentityHashMap<>()), issues);

		issues.sort(Comparator.comparingInt(ValidationIssue::startOffset));
		return List.copyOf(issues);
	}

	private void visit(Node node, Set<NodeFilter> ignoringFilters, List<ValidationIssue> issues) {
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
				issues.addAll(validator.validate(node));
			}
		}

		for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
			visit(child, ignoringFilters, issues);
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