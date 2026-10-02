/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import com.advantest.markdown.MarkdownCustomization;
import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.markdown.service.resources.ResourceResolverRegistry;
import com.advantest.markdown.service.validation.uri.DefaultHttpUriReachabilityValidator;
import com.advantest.markdown.service.validation.uri.HttpUriReachabilityChecker;
import com.advantest.markdown.service.validation.uri.HttpUriSyntaxValidator;
import com.advantest.markdown.service.validation.uri.UnknownSchemeUriValidator;
import com.advantest.markdown.service.validation.uri.UriReachabilityChecker;
import com.advantest.markdown.service.validation.uri.UriTarget;
import com.advantest.markdown.service.validation.anchor.AnchorTarget;
import com.advantest.markdown.service.validation.anchor.AnchorValidator;
import com.advantest.markdown.service.validation.anchor.MarkdownSectionAnchorValidator;
import com.advantest.markdown.service.validation.uri.UriValidator;
import com.advantest.resources.FileSchemeUriResolver;
import com.advantest.resources.LocalFileSystemResourceResolver;
import com.advantest.resources.RelativePathResourceResolver;
import com.advantest.resources.Resource;
import com.advantest.resources.ResourceResolver;
import com.advantest.resources.UnresolvedResource;
import com.advantest.resources.UriResolver;
import com.advantest.markdown.service.validation.MarkdownValidation;
import com.advantest.markdown.service.validation.ValidationIssue;
import com.advantest.plantuml.PlantUmlSettings;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.DataKey;
import com.vladsch.flexmark.util.data.NullableDataKey;
import com.vladsch.flexmark.util.misc.Extension;

import net.sourceforge.plantuml.security.SecurityProfile;

/**
 * Facade offering Markdown parsing, HTML rendering and validation features.
 * 
 * <p>Use the parameter-less constructor to get a service with the default configuration.
 * If the underlying Markdown parser and HTML renderer needs to be customized, e.g. by adding
 * further flexmark extensions or by setting flexmark options, use a builder:</p>
 * 
 * <pre>
 * MarkdownService service = MarkdownService.builderNotCheckingUriReachability()
 *         .withExtension(SomeFlexmarkExtension.create())
 *         .withOption(SomeExtension.SOME_OPTION, "some value")
 *         .build();
 * </pre>
 * 
 * <p>Neither that builder nor the parameter-less constructor asks an address a document names
 * whether it is there, so nothing here needs a network. Whoever wants the addresses asked about
 * says so, which is what {@link #builderCheckingUriReachability()} is for.</p>
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

	private final UriReachabilityChecker uriReachabilityChecker;

	/** Everything this service is made of that remembers answers, in no particular order. */
	private final List<CachesHolder> cachesHolders;

	/**
	 * Creates a service using the default Markdown parser and HTML renderer configuration
	 * and resolving references in the local file system, without asking an address a document
	 * names whether it is there.
	 * Use {@link #builderNotCheckingUriReachability()} if you need to customize the parser, the
	 * renderer or the resolver, and {@link #builderCheckingUriReachability()} if the addresses
	 * shall be asked about.
	 */
	public MarkdownService() {
		this(new MarkdownParserAndHtmlRenderer(), ResourceResolverRegistry.ofLocalFileSystem());
	}

	/**
	 * Creates a service delegating to the given Markdown parser and HTML renderer and resolving
	 * references with the given resolvers, without asking any address whether it is there.
	 * 
	 * @param parserAndRenderer the parser and renderer to delegate to, must not be <code>null</code>
	 * @param resourceResolvers the resolvers of everything a document refers to, must not be
	 *                          <code>null</code>
	 */
	MarkdownService(MarkdownParserAndHtmlRenderer parserAndRenderer,
			ResourceResolverRegistry resourceResolvers) {
		this(parserAndRenderer, resourceResolvers, null, List.of(), List.of());
	}

	/**
	 * Creates a service delegating to the given Markdown parser and HTML renderer, resolving
	 * references with the given resolvers, asking the given check whether an address is there,
	 * handing a target naming a scheme to the given URI validators and a fragment of a target to
	 * the given anchor validators.
	 * 
	 * @param parserAndRenderer the parser and renderer to delegate to, must not be <code>null</code>
	 * @param resourceResolvers the resolvers of everything a document refers to, must not be
	 *                          <code>null</code>
	 * @param uriReachabilityChecker the check asking an address whether it is there, may be
	 *                               <code>null</code>, in which case no address is asked about
	 * @param uriValidators the validators of a target naming a scheme, asked in the given order, so
	 *                      that the first one saying it is responsible answers for a target; the
	 *                      builder hands them over in the reverse of the order they were registered
	 *                      in, must not be <code>null</code>
	 * @param anchorValidators the validators of what a link names inside its target, asked in the
	 *                         given order, so that the first one saying it is responsible answers
	 *                         for a target; the builder hands them over in the reverse of the order
	 *                         they were registered in, must not be <code>null</code>
	 */
	MarkdownService(MarkdownParserAndHtmlRenderer parserAndRenderer,
			ResourceResolverRegistry resourceResolvers,
			UriReachabilityChecker uriReachabilityChecker,
			List<UriValidator> uriValidators,
			List<AnchorValidator> anchorValidators) {
		if (parserAndRenderer == null || resourceResolvers == null || uriValidators == null
				|| anchorValidators == null) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}
		useTheUsualDotExecutableWhereNobodyNamedOne();
		this.parserAndRenderer = parserAndRenderer;
		this.validation = new MarkdownValidation(parserAndRenderer, resourceResolvers,
				withShippedValidators(uriValidators, uriReachabilityChecker),
				withShippedAnchorValidators(anchorValidators, parserAndRenderer));
		this.uriReachabilityChecker = uriReachabilityChecker;
		this.cachesHolders = cachesHoldersAmong(uriReachabilityChecker, uriValidators,
				anchorValidators);
	}

	/**
	 * Picks out everything this service was built with that remembers answers, so that
	 * {@link #clearCaches()} reaches it without asking anybody again what the service is made of.
	 */
	private static List<CachesHolder> cachesHoldersAmong(UriReachabilityChecker uriReachabilityChecker,
			List<UriValidator> uriValidators, List<AnchorValidator> anchorValidators) {

		List<CachesHolder> holders = new ArrayList<>();
		Stream.concat(Stream.of(uriReachabilityChecker),
						Stream.concat(uriValidators.stream(), anchorValidators.stream()))
				.filter(CachesHolder.class::isInstance)
				.map(CachesHolder.class::cast)
				.forEach(holders::add);

		return List.copyOf(holders);
	}

	/**
	 * Tells everything this service is made of that remembers answers to forget them, so that the
	 * next document naming an address costs the question again.
	 * 
	 * <p>The check asking an address whether it is there is told, and so is every
	 * {@link Builder#withUriValidator(UriValidator) registered validator} and every
	 * {@link Builder#withAnchorValidator(AnchorValidator) registered anchor validator} saying with
	 * {@link CachesHolder} that it remembers something. A part that remembers nothing is not
	 * touched, and a service nothing of which remembers anything does nothing here.</p>
	 * 
	 * <p>Say this where the answers may have gone stale, e.g. in a program running for a day, or
	 * where a document that was reported unreachable is known to be there by now. It may be said
	 * while documents are being validated; what is already being waited for is not taken back.</p>
	 */
	public void clearCaches() {
		this.cachesHolders.forEach(CachesHolder::clearCaches);
	}

	/**
	 * Puts the validators this library ships behind the ones a caller registered, so that a caller
	 * knowing a target better answers for it first. What a web address has to look like is said
	 * either way, because reading the target text asks nobody; whether an address is there is only
	 * asked where a caller said that addresses may be asked about at all. A scheme nobody knows is
	 * reported either way as well, because saying so costs nothing.
	 * 
	 * <p>The two validators about web addresses claim targets that exclude each other &mdash; one
	 * a text meant as an address and readable as none, the other an address that can be read
	 * &mdash; so the order between them says nothing, and neither of them keeps a target from the
	 * other.</p>
	 */
	private static List<UriValidator> withShippedValidators(List<UriValidator> registeredValidators,
			UriReachabilityChecker uriReachabilityChecker) {

		List<UriValidator> validatorsAskedInOrder = new ArrayList<>(registeredValidators);
		validatorsAskedInOrder.add(new HttpUriSyntaxValidator());
		if (uriReachabilityChecker != null) {
			validatorsAskedInOrder.add(new DefaultHttpUriReachabilityValidator(uriReachabilityChecker));
		}
		validatorsAskedInOrder.add(new UnknownSchemeUriValidator());

		return List.copyOf(validatorsAskedInOrder);
	}

	/**
	 * Puts the anchor validator this library ships behind the ones a caller registered, so that a
	 * caller knowing a target better answers for it first. The shipped one looks into a Markdown
	 * file and asks the parser of this service which file that is.
	 */
	private static List<AnchorValidator> withShippedAnchorValidators(
			List<AnchorValidator> registeredValidators, MarkdownParserAndHtmlRenderer parserAndRenderer) {

		List<AnchorValidator> validatorsAskedInOrder = new ArrayList<>(registeredValidators);
		validatorsAskedInOrder.add(
				new MarkdownSectionAnchorValidator(parserAndRenderer.getMarkdownFileExtensions()));

		return List.copyOf(validatorsAskedInOrder);
	}

	/**
	 * Tells which check this service asks whether an address is there.
	 * 
	 * @return the check, or {@link Optional#empty()} if no address is asked about at all
	 */
	Optional<UriReachabilityChecker> getUriReachabilityChecker() {
		return Optional.ofNullable(this.uriReachabilityChecker);
	}

	/**
	 * Names the Graphviz <code>dot</code> executable PlantUML runs to lay a diagram out.
	 * 
	 * <p>This is a setting of the machine rather than of a service: PlantUML keeps it once for the
	 * whole process, so it applies to every diagram rendered afterwards, no matter which service
	 * renders it. That is why it is said here and not on a builder, which would promise a scope
	 * that does not exist.</p>
	 * 
	 * <p>What is named here wins over the <code>GRAPHVIZ_DOT</code> system property and over the
	 * environment variable of that name, so that an application offering its user a setting can
	 * let the user's choice win over what the machine happens to say. Where nobody names one, a
	 * service built afterwards takes the place Graphviz is usually installed at on the operating
	 * system it runs on, and where nothing is there either, PlantUML falls back to the Graphviz it
	 * carries inside itself, which lays a graph out differently than an installed one does.</p>
	 * 
	 * @param dotExecutablePath the path of the executable, which must name an existing file that
	 *                          can be run; <code>null</code> or blank takes the name back
	 * @throws IllegalArgumentException if the given path names nothing that can be run
	 */
	public static void setDotExecutable(String dotExecutablePath) {
		PlantUmlSettings.setDotExecutable(dotExecutablePath);
	}

	/**
	 * Tells which Graphviz <code>dot</code> executable PlantUML runs, as far as anybody named one.
	 * 
	 * @return the path of the executable, or {@link Optional#empty()} where nobody named one
	 */
	public static Optional<String> getDotExecutable() {
		return Optional.ofNullable(PlantUmlSettings.getDotExecutable());
	}

	/**
	 * Sets the security profile PlantUML applies, which decides whether a diagram may read a file
	 * of the machine it runs on and whether it may read an address.
	 * 
	 * <p>The choice belongs to whoever runs this service and it is not one that may be made in
	 * passing: {@link SecurityProfile#INSECURE} lets a diagram read any file of the machine, which
	 * is what an editor working on the files of its user wants and what a server rendering a
	 * document somebody sent it must never do. Where nothing is said here, the profile PlantUML
	 * chooses itself applies, so this library never raises it quietly.</p>
	 * 
	 * <p>PlantUML reads its profile once and keeps it for the rest of the process, so this has to
	 * be said before the first diagram is rendered.</p>
	 * 
	 * @param profile the profile to apply, must not be <code>null</code>
	 * @throws IllegalStateException if PlantUML already read a different profile
	 */
	public static void setPlantUmlSecurityProfile(SecurityProfile profile) {
		PlantUmlSettings.setSecurityProfile(profile);
	}

	/**
	 * Tells the security profile PlantUML applies, reading it where it was not read yet, so that
	 * it cannot be changed afterwards.
	 * 
	 * @return the profile that applies, never <code>null</code>
	 */
	public static SecurityProfile getPlantUmlSecurityProfile() {
		return PlantUmlSettings.getSecurityProfile();
	}

	/**
	 * Sets the addresses a diagram may read, replacing whatever was allowed before.
	 * 
	 * <p>They only have an effect under the security profile {@link SecurityProfile#ALLOWLIST},
	 * where a diagram may read the addresses named here and no other.</p>
	 * 
	 * @param urls the addresses a diagram may read, must not be <code>null</code>
	 */
	public static void setPlantUmlUrlAllowList(List<String> urls) {
		PlantUmlSettings.setUrlAllowList(urls);
	}

	/**
	 * Tells which addresses a diagram may read, as far as anybody allowed one.
	 * 
	 * @return the allowed addresses in the order they were allowed in, never <code>null</code>
	 */
	public static List<String> getPlantUmlUrlAllowList() {
		return PlantUmlSettings.getUrlAllowList();
	}

	/**
	 * Names the place Graphviz is usually installed at, so that a diagram is laid out by the
	 * Graphviz of the machine rather than by the one PlantUML carries inside itself. Whatever
	 * anybody named before stays, and a place nothing is installed at is no reason to complain:
	 * PlantUML then renders as it did before.
	 */
	private static void useTheUsualDotExecutableWhereNobodyNamedOne() {
		if (PlantUmlSettings.getDotExecutable() != null) {
			return;
		}

		String usualPath = usualDotExecutablePath();
		if (usualPath == null) {
			return;
		}

		File executable = new File(usualPath);
		if (executable.isFile() && executable.canExecute()) {
			PlantUmlSettings.setDotExecutable(usualPath);
		}
	}

	/**
	 * Tells where Graphviz is usually installed on the operating system this runs on, whether
	 * anything is installed there or not.
	 * 
	 * @return the path, or <code>null</code> on an operating system this library knows no usual
	 *         place of, where PlantUML decides alone
	 */
	static String usualDotExecutablePath() {
		String operatingSystem = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (operatingSystem.startsWith("windows")) {
			return "C:\\Program Files\\Graphviz\\bin\\dot.exe";
		}
		if (operatingSystem.startsWith("mac")) {
			return "/usr/local/bin/dot";
		}
		if (operatingSystem.contains("nix") || operatingSystem.contains("nux")
				|| operatingSystem.contains("aix")) {
			return "/usr/bin/dot";
		}
		return null;
	}

	/**
	 * Creates a builder for a service with a customized Markdown parser and HTML renderer, which
	 * does not ask any address a document names whether it is there.
	 * 
	 * <p>A document naming a web address is read and validated without a network, and an address
	 * leading nowhere stays unreported, because asking costs time and a network, which is nothing
	 * a caller should pay without saying so. Whoever wants the addresses asked about starts from
	 * {@link #builderCheckingUriReachability()} or says
	 * {@link Builder#withUriReachabilityCheck()} here.</p>
	 * 
	 * @return a new builder, never <code>null</code>
	 */
	public static Builder builderNotCheckingUriReachability() {
		return new Builder();
	}

	/**
	 * Creates a builder for a service with a customized Markdown parser and HTML renderer, which
	 * asks every address a document names whether it is there.
	 * 
	 * <p>This is {@link #builderNotCheckingUriReachability()} plus
	 * {@link Builder#withUriReachabilityCheck()}, so the shipped check gives an address the times
	 * it defaults to and remembers every answer. A caller needing other times, or a check of its
	 * own, starts from the other builder and says so there.</p>
	 * 
	 * <p>A service built from here needs a network and takes the time an address takes, so a
	 * caller that may run without one, e.g. a test, starts from
	 * {@link #builderNotCheckingUriReachability()} instead.</p>
	 * 
	 * @return a new builder, never <code>null</code>
	 */
	public static Builder builderCheckingUriReachability() {
		return new Builder().withUriReachabilityCheck();
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
		return waitFor(this.validation.validate(markdownDocument));
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
		return waitFor(this.validation.validate(parseMarkdown(markdownSourceCode)));
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
		return waitFor(this.validation.validate(parseMarkdown(markdownSourceCode, documentResource)));
	}

	/**
	 * Checks the given parsed Markdown document without waiting for the answers of the checks that
	 * have to ask something slow, e.g. a web address.
	 * 
	 * <p>The document is walked before this method returns, so it must not be changed until the
	 * promised findings have arrived. Everything that is asked over the network is asked while the
	 * walk goes on, and the promise is kept once the last answer is there.</p>
	 * 
	 * <p>The promised findings are ordered by their start offset, just like those of
	 * {@link #validateMarkdown(Document)}, so that two runs over equal source code promise equal
	 * lists.</p>
	 * 
	 * @param markdownDocument the parsed Markdown document to be checked, must not be <code>null</code>
	 * @return the promise of the problems found, ordered by start offset, empty if there are none,
	 *         never <code>null</code> and not modifiable
	 * @throws IllegalArgumentException if the given document is <code>null</code>
	 */
	public CompletableFuture<List<ValidationIssue>> validateMarkdownAsync(Document markdownDocument) {
		return this.validation.validate(markdownDocument);
	}

	/**
	 * Convenience method parsing the given Markdown source code of unknown origin and checking it
	 * without waiting for the slow checks.
	 * 
	 * @param markdownSourceCode the Markdown source code to be checked, must not be <code>null</code>
	 * @return the promise of the problems found, ordered by start offset, empty if there are none,
	 *         never <code>null</code> and not modifiable
	 * @throws IllegalArgumentException if the given source code is <code>null</code>
	 * @see #validateMarkdownAsync(Document)
	 */
	public CompletableFuture<List<ValidationIssue>> validateMarkdownAsync(String markdownSourceCode) {
		if (markdownSourceCode == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		return this.validation.validate(parseMarkdown(markdownSourceCode));
	}

	/**
	 * Convenience method parsing the given Markdown source code that came from the given resource
	 * and checking it without waiting for the slow checks.
	 * 
	 * @param markdownSourceCode the Markdown source code to be checked, must not be <code>null</code>
	 * @param documentResource the resource the source code came from, must not be <code>null</code>,
	 *                         pass {@link UnresolvedResource#UNKNOWN_DOCUMENT} if it is unknown
	 * @return the promise of the problems found, ordered by start offset, empty if there are none,
	 *         never <code>null</code> and not modifiable
	 * @throws IllegalArgumentException if one of the arguments is <code>null</code>
	 * @see #validateMarkdownAsync(Document)
	 */
	public CompletableFuture<List<ValidationIssue>> validateMarkdownAsync(String markdownSourceCode,
			Resource documentResource) {
		if (markdownSourceCode == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		return this.validation.validate(parseMarkdown(markdownSourceCode, documentResource));
	}

	/**
	 * Waits for the promised findings and hands them over.
	 * 
	 * <p>What a check failed with is handed on as it was thrown, rather than wrapped in what
	 * waiting for a promise says about a broken one, so that a caller sees the failure of the check
	 * and not the failure of the waiting.</p>
	 */
	private static List<ValidationIssue> waitFor(CompletableFuture<List<ValidationIssue>> promisedIssues) {
		try {
			return promisedIssues.join();
		} catch (CompletionException waitingFailed) {
			switch (waitingFailed.getCause()) {
				case RuntimeException failure -> throw failure;
				case Error failure -> throw failure;
				case null, default -> throw waitingFailed;
			}
		}
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

		private RelativePathResourceResolver relativePathResolver;

		private final List<UriResolver> uriResolvers = new ArrayList<>();

		private UriReachabilityChecker uriReachabilityChecker;

		private final List<UriValidator> uriValidators = new ArrayList<>();

		private final List<AnchorValidator> anchorValidators = new ArrayList<>();

		private Builder() {
		}

		/**
		 * Resolves everything a Markdown document refers to in the file system of the machine this
		 * code runs on, which is what happens anyway if nothing else is said. Say it to say it.
		 * 
		 * @return this builder for method chaining, never <code>null</code>
		 */
		public Builder withLocalFileSystemResourceResolver() {
			return withRelativePathResourceResolver(new LocalFileSystemResourceResolver());
		}

		/**
		 * Resolves a reference that names no scheme with the given resolver, which says what such
		 * a reference means as seen from the document carrying it &ndash; a file next to that
		 * document, or an address built from the address of that document. There is one such
		 * resolver, so a later call replaces an earlier one.
		 * 
		 * <p>A reference naming a file with the <code>file</code> scheme is handed to the same
		 * resolver, because it is the same file however it is named. A path naming its resource on
		 * its own, on the other hand, reaches no resolver at all: it leads to the resource the
		 * author meant on one machine and nowhere else, and is reported rather than looked
		 * for.</p>
		 * 
		 * @param resolver the resolver of a reference without a scheme, must not be
		 *                 <code>null</code>
		 * @return this builder for method chaining, never <code>null</code>
		 * @throws IllegalArgumentException if the given resolver is <code>null</code>
		 */
		public Builder withRelativePathResourceResolver(RelativePathResourceResolver resolver) {
			if (resolver == null) {
				throw new IllegalArgumentException("Argument must not be null.");
			}
			this.relativePathResolver = resolver;
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
		 * Asks the addresses a document names whether they are there, with the shipped check.
		 * 
		 * <p>No address is asked about unless this method,
		 * {@link #withUriReachabilityCheck(Duration, Duration)} or
		 * {@link #withUriReachabilityCheck(UriReachabilityChecker)} is called: asking costs time
		 * and needs a network, which is nothing a caller should pay without saying so. The shipped
		 * check gives an address the times it defaults to, and it remembers every answer, so an
		 * address named by many documents is asked about once. Whoever needs other times says them
		 * with {@link #withUriReachabilityCheck(Duration, Duration)}.</p>
		 * 
		 * <p>Saying this also puts the shipped validator of web addresses to work: from now on a
		 * target beginning with <code>http</code> or <code>https</code> is reported when it cannot
		 * be read as an address, when it does not answer, or when it answers that there is nothing
		 * there.</p>
		 * 
		 * <p>That validator is asked last and only about a target no
		 * {@link #withUriValidator(UriValidator) registered validator} claimed, so a validator
		 * claiming an address keeps it from being asked about at all.</p>
		 * 
		 * @return this builder for method chaining, never <code>null</code>
		 */
		public Builder withUriReachabilityCheck() {
			return withUriReachabilityCheck(new HttpUriReachabilityChecker());
		}

		/**
		 * Asks the addresses a document names whether they are there, with the shipped check, and
		 * gives an address the times said here rather than the ones that check defaults to.
		 * 
		 * <p>Everything {@link #withUriReachabilityCheck()} says holds, only the two times differ.
		 * What is long enough depends on the network the caller sits in and on what its addresses
		 * lead to, so a caller whose addresses are slower, or who wants a dead one given up on
		 * sooner, says so here instead of writing a check of its own.
		 * {@link HttpUriReachabilityChecker#DEFAULT_CONNECT_TIMEOUT} and
		 * {@link HttpUriReachabilityChecker#DEFAULT_ANSWER_TIMEOUT} are there so that changing one
		 * of the two does not mean restating the other.</p>
		 * 
		 * @param untilConnected how long the far side has to accept the connection, must not be
		 *        <code>null</code> and must be longer than nothing
		 * @param untilAnswered how long the far side has to answer, must not be <code>null</code>,
		 *        must be longer than nothing and must not be shorter than
		 *        <code>untilConnected</code>, because connecting is part of answering
		 * @return this builder for method chaining, never <code>null</code>
		 * @throws IllegalArgumentException if one of the arguments is <code>null</code>, if one of
		 *         them is zero or negative, or if the time to answer is shorter than the time to
		 *         connect
		 */
		public Builder withUriReachabilityCheck(Duration untilConnected, Duration untilAnswered) {
			return withUriReachabilityCheck(
					new HttpUriReachabilityChecker(untilConnected, untilAnswered));
		}

		/**
		 * Asks the addresses a document names whether they are there, with the given check, e.g.
		 * one going through a proxy, one asking a service instead of the address itself, or one a
		 * test answers for.
		 * 
		 * <p>There is one such check, so a later call replaces an earlier one. It is asked from
		 * several threads at once and for the same address again and again, so it has to bear the
		 * former and is expected to remember an answer rather than to ask again.</p>
		 * 
		 * <p>Saying this also puts the shipped validator of web addresses to work, the same way
		 * {@link #withUriReachabilityCheck()} does, and that validator is asked only about a target
		 * no {@link #withUriValidator(UriValidator) registered validator} claimed.</p>
		 * 
		 * @param checker the check to be used, must not be <code>null</code>
		 * @return this builder for method chaining, never <code>null</code>
		 * @throws IllegalArgumentException if the given check is <code>null</code>
		 */
		public Builder withUriReachabilityCheck(UriReachabilityChecker checker) {
			if (checker == null) {
				throw new IllegalArgumentException("Argument must not be null.");
			}
			this.uriReachabilityChecker = checker;
			return this;
		}

		/**
		 * Adds a validator for targets naming a scheme, e.g. one checking the addresses of an
		 * issue tracker.
		 * 
		 * <p>Several validators may know the same scheme and tell each other apart by the host or
		 * by the beginning of the address, so the one added last that says it is
		 * {@link UriValidator#isResponsibleFor(UriTarget) responsible} answers for a target, and it
		 * answers alone. A target naming a scheme this library knows and no validator claims is left
		 * alone; a target naming a scheme nobody knows is reported, because nothing would ever look
		 * at it.</p>
		 * 
		 * <p>A validator added here is asked before the shipped validator of web addresses, so
		 * claiming an address keeps it from being asked whether it is there
		 * ({@link #withUriReachabilityCheck()}), whether the validator reports anything about it or
		 * not.</p>
		 * 
		 * @param validator the validator to be added, must not be <code>null</code>
		 * @return this builder for method chaining, never <code>null</code>
		 * @throws IllegalArgumentException if the given validator is <code>null</code>
		 */
		public Builder withUriValidator(UriValidator validator) {
			if (validator == null) {
				throw new IllegalArgumentException("Argument must not be null.");
			}
			this.uriValidators.add(validator);
			return this;
		}

		/**
		 * Adds a validator for what a link names inside its target, e.g. one finding a method in a
		 * source file.
		 * 
		 * <p>Several validators may answer for the same kind of target and tell each other apart by
		 * the path, so the one added last that says it is
		 * {@link AnchorValidator#isResponsibleFor(AnchorTarget) responsible} answers for a target,
		 * and it answers alone. Looking into a Markdown file is what this library ships, so a
		 * validator added here is asked before that one. A target no validator claims is reported,
		 * because nothing would ever look at what the link names.</p>
		 * 
		 * @param validator the validator to be added, must not be <code>null</code>
		 * @return this builder for method chaining, never <code>null</code>
		 * @throws IllegalArgumentException if the given validator is <code>null</code>
		 */
		public Builder withAnchorValidator(AnchorValidator validator) {
			if (validator == null) {
				throw new IllegalArgumentException("Argument must not be null.");
			}
			this.anchorValidators.add(validator);
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
			RelativePathResourceResolver pathResolver = this.relativePathResolver != null
					? this.relativePathResolver
					: new LocalFileSystemResourceResolver();

			List<UriResolver> allUriResolvers = new ArrayList<>();
			allUriResolvers.add(new FileSchemeUriResolver(pathResolver));
			allUriResolvers.addAll(this.uriResolvers);

			ResourceResolverRegistry resolvers =
					new ResourceResolverRegistry(pathResolver, allUriResolvers);

			this.parserAndRendererBuilder.withRelativePathResourceResolver(pathResolver);

			// the validator registered last is asked first, so that a validator answering for a
			// few addresses can be put in front of one answering for all of them
			List<UriValidator> validatorsAskedInOrder = new ArrayList<>(this.uriValidators);
			Collections.reverse(validatorsAskedInOrder);

			List<AnchorValidator> anchorValidatorsAskedInOrder = new ArrayList<>(this.anchorValidators);
			Collections.reverse(anchorValidatorsAskedInOrder);

			return new MarkdownService(this.parserAndRendererBuilder.build(), resolvers,
					this.uriReachabilityChecker, validatorsAskedInOrder, anchorValidatorsAskedInOrder);
		}

	}

}
