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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.markdown.service.resources.ResourceResolverRegistry;
import com.advantest.markdown.service.resources.walk.ResourceFilter;
import com.advantest.markdown.service.validation.anchor.AnchorValidator;
import com.advantest.markdown.service.validation.uri.UriReachabilityChecker;
import com.advantest.markdown.service.validation.uri.UriValidator;
import com.advantest.resources.ResourceContentsReader;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;

/**
 * Applies the validation rules to a parsed Markdown document.
 * 
 * <p>The rules themselves live in {@link MarkdownValidator}s this one is composed of, each
 * covering one kind of Markdown construct. This class walks the document once and offers every
 * node to the validators triggered by it, leaving out what a validator
 * {@link MarkdownValidator#getIgnoredNodes() ignores}.</p>
 * 
 * <p>The rules are applied in a {@link #createRun() run}, which holds what was read and parsed
 * while it checks its documents. The rules themselves hold nothing of a document and live as long
 * as the service they belong to.</p>
 */
public class MarkdownValidationRules {

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
	public MarkdownValidationRules(MarkdownParserAndHtmlRenderer parserAndRenderer,
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
	 * @param uriValidators the validators of a target naming a scheme, asked in the given order, so
	 *                      that the first one saying it is responsible answers for a target, must
	 *                      not be <code>null</code>
	 * @param anchorValidators the validators of what a link names inside its target, asked in the
	 *                         given order, so that the first one saying it is responsible answers
	 *                         for a target, must not be <code>null</code>
	 */
	public MarkdownValidationRules(MarkdownParserAndHtmlRenderer parserAndRenderer,
			ResourceResolverRegistry resourceResolvers,
			List<UriValidator> uriValidators,
			List<AnchorValidator> anchorValidators) {
		this(parserAndRenderer,
				List.of(new MarkdownLinkValidator(parserAndRenderer, resourceResolvers, uriValidators,
								anchorValidators),
						new MarkdownAnchorValidator()));
	}

	/**
	 * Creates a validation applying the given validators.
	 * 
	 * @param parserAndRenderer the parser reading a document a link points into, must not be
	 *                          <code>null</code>
	 * @param validators the validators to be applied, must not be <code>null</code>
	 */
	MarkdownValidationRules(MarkdownParserAndHtmlRenderer parserAndRenderer,
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
	 * Creates a run applying these rules, which reads and parses everything the documents handed to
	 * it refer to at most once, and asks no address whether it is there.
	 * 
	 * @return a new run, never <code>null</code>, to be closed by the caller
	 */
	public MarkdownValidationRun createRun() {
		return createRun(null);
	}

	/**
	 * Creates a run applying these rules, which reads and parses everything the documents handed to
	 * it refer to at most once, and asks an address whether it is there through the given check,
	 * {@link UriReachabilityChecker#openForRun(java.util.concurrent.Executor) opened} for the run.
	 * 
	 * @param uriReachabilityChecker the check asking an address whether it is there, may be
	 *                               <code>null</code>, in which case no address is asked about
	 * @return a new run, never <code>null</code>, to be closed by the caller
	 */
	public MarkdownValidationRun createRun(UriReachabilityChecker uriReachabilityChecker) {
		return createRun(uriReachabilityChecker, ResourceContentsReader.FROM_THE_RESOURCE);
	}

	/**
	 * Creates a run applying these rules, which reads everything the documents handed to it refer
	 * to at most once, through the given reader, parses it at most once, and asks an address
	 * whether it is there through the given check,
	 * {@link UriReachabilityChecker#openForRun(java.util.concurrent.Executor) opened} for the run.
	 * 
	 * <p>The reader decides who answers with the contents of a resource, e.g. the text an editor
	 * holds and did not save yet rather than the resource itself. It is to answer with one state of
	 * every resource for as long as the run lives, so that the run checks one state of all of
	 * them.</p>
	 * 
	 * @param uriReachabilityChecker the check asking an address whether it is there, may be
	 *                               <code>null</code>, in which case no address is asked about
	 * @param contentsReader what answers with the contents of a resource the run reads, must not be
	 *                       <code>null</code>
	 * @return a new run, never <code>null</code>, to be closed by the caller
	 * @throws IllegalArgumentException if the given reader is <code>null</code>
	 */
	public MarkdownValidationRun createRun(UriReachabilityChecker uriReachabilityChecker,
			ResourceContentsReader contentsReader) {
		return new MarkdownValidationRun(this, this.parserAndRenderer, uriReachabilityChecker, contentsReader);
	}

	/**
	 * Does what {@link #createRun(UriReachabilityChecker, ResourceContentsReader)} does, with the
	 * given filter deciding which folders a walk of the run enters and which files it validates.
	 * 
	 * @param uriReachabilityChecker the check asking an address whether it is there, may be
	 *                               <code>null</code>, in which case no address is asked about
	 * @param contentsReader what answers with the contents of a resource the run reads, must not be
	 *                       <code>null</code>
	 * @param resourceFilter the filter of the run's walks, must not be <code>null</code>
	 * @return a new run, never <code>null</code>, to be closed by the caller
	 * @throws IllegalArgumentException if the given reader or filter is <code>null</code>
	 * @see MarkdownValidationRun#validateTree(java.nio.file.Path)
	 */
	public MarkdownValidationRun createRun(UriReachabilityChecker uriReachabilityChecker,
			ResourceContentsReader contentsReader, ResourceFilter resourceFilter) {
		return new MarkdownValidationRun(this, this.parserAndRenderer, uriReachabilityChecker, contentsReader,
				resourceFilter);
	}

	/**
	 * Checks the given Markdown document.
	 * 
	 * <p>A validator that cannot answer at once is not waited for where it is asked: the document
	 * is walked to its end, and the promises collected on the way are waited for afterwards, all of
	 * them at once. What comes back is ordered by where it was found, so that the order does not
	 * depend on who answered first.</p>
	 * 
	 * <p>A validator that fails loses its own findings for the node it was asked about, and the run
	 * goes on: the other validators are asked, the walk reaches the end of the document, and what
	 * the other rules found is reported. A failure is not said out loud anywhere yet.</p>
	 * 
	 * <p>Every validator is handed the given context, which belongs to the run the document is
	 * checked in, so that whatever that run read or parsed is read and parsed once, however many
	 * documents of the run ask for it.</p>
	 * 
	 * @param document the parsed Markdown document to be checked, must not be <code>null</code>
	 * @param context what the run the document is checked in knows, must not be <code>null</code>
	 * @return the promise of the problems found, ordered by start offset, never <code>null</code>
	 *         and kept with a list that is not modifiable
	 * @throws IllegalArgumentException if one of the arguments is <code>null</code>
	 */
	CompletableFuture<List<ValidationIssue>> validate(Document document, MarkdownValidationContext context) {
		if (document == null || context == null) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}

		ValidationIssueCollector issues = new ValidationIssueCollector();

		visit(document, Collections.newSetFromMap(new IdentityHashMap<>()), context, issues);

		return issues.promised().thenApply(MarkdownValidationRules::orderedByPosition);
	}

	/**
	 * Puts what was found into the order of the document. The findings are collected in the order
	 * the walk met them, which sorting keeps where two of them share a start offset.
	 */
	private static List<ValidationIssue> orderedByPosition(List<ValidationIssue> issues) {
		List<ValidationIssue> orderedIssues = new ArrayList<>(issues);
		orderedIssues.sort(Comparator.comparingInt(ValidationIssue::startOffset));
		return List.copyOf(orderedIssues);
	}

	private void visit(Node node, Set<NodeFilter> ignoringFilters, MarkdownValidationContext context,
			ValidationIssueCollector issues) {
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
			if (!ignoringFilters.contains(validator.getIgnoredNodes())
					&& ValidatorGuard.saysItIsResponsible(validator, () -> validator.isValidatorFor(node))) {
				issues.addPromised(
						ValidatorGuard.findingsOf(validator, () -> validator.validate(node, context)));
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