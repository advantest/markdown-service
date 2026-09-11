/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.advantest.markdown.service.parsing.LinkTarget;
import com.advantest.markdown.service.parsing.MarkdownParsingTools;
import com.advantest.markdown.service.parsing.RegexMatch;
import com.advantest.markdown.service.resources.ResourceResolverRegistry;
import com.advantest.markdown.service.validation.anchor.AnchorTarget;
import com.advantest.markdown.service.validation.anchor.AnchorValidator;
import com.advantest.markdown.service.validation.anchor.MarkdownSectionAnchors;
import com.advantest.markdown.service.validation.resource.AbsolutePathValidator;
import com.advantest.markdown.service.validation.resource.RelativePathValidator;
import com.advantest.markdown.service.validation.uri.UriTarget;
import com.advantest.markdown.service.validation.uri.UriValidator;
import com.advantest.markdown.service.utils.TextUtils;
import com.advantest.resources.Resource;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.ImageRef;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.LinkRef;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.Reference;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.sequence.BasedSequence;

/**
 * Checks the links, images and link reference definitions in Markdown source code.
 * 
 * <p>Which construct is checked is decided by the parser, the rules themselves still work on the
 * text of the construct: the parser reads the target of <code>[label](   )</code> as an empty one,
 * while the rule is to mark the blanks that are there, so the rule needs the text.</p>
 * 
 * <p>A link reference definition without a target, e.g. <code>[label]:</code>, is not a definition
 * for the parser and is therefore looked for in the paragraph it ends up in.</p>
 */
class MarkdownLinkValidator implements MarkdownValidator {

	private static final Set<Class<? extends Node>> TRIGGERING_NODE_TYPES = Set.of(
			Link.class, Image.class, LinkRef.class, ImageRef.class,
			Reference.class, Paragraph.class, Text.class, Document.class);

	private static final String MESSAGE_EMPTY_LINK_TARGET = "The target file path or URL is empty.";

	private static final String MESSAGE_EMPTY_REFERENCE_LINK_LABEL =
			"The reference link label is empty. Please create a link reference definition like"
			+ " \"[ReferenceLinkLabel]: https://plantuml.com\""
			+ " and use that reference link label in your link,"
			+ " e.g. \"[your link text][ReferenceLinkLabel]\" or \"[ReferenceLinkLabel]\".";

	private final RelativePathValidator relativePathValidator;

	private final AbsolutePathValidator absolutePathValidator = new AbsolutePathValidator();

	private final List<UriValidator> uriValidators;

	private final List<AnchorValidator> anchorValidators;

	/**
	 * Creates the validator, resolving everything a link points to with the given resolvers and
	 * checking no target naming a scheme.
	 * 
	 * @param resourceResolvers the resolvers of the surrounding environment, must not be
	 *                          <code>null</code>
	 */
	MarkdownLinkValidator(ResourceResolverRegistry resourceResolvers) {
		this(resourceResolvers, List.of(), List.of());
	}

	/**
	 * Creates the validator, resolving everything a link points to with the given resolvers,
	 * handing a target naming a scheme to the given URI validators and a fragment of a target to
	 * the given anchor validators.
	 * 
	 * @param resourceResolvers the resolvers of the surrounding environment, must not be
	 *                          <code>null</code>
	 * @param uriValidators the validators of a target naming a scheme, the first one saying it is
	 *                      responsible answers for a target, must not be <code>null</code>
	 * @param anchorValidators the validators of what a link names inside its target, the first one
	 *                         saying it is responsible answers for a target, must not be
	 *                         <code>null</code>
	 */
	MarkdownLinkValidator(ResourceResolverRegistry resourceResolvers, List<UriValidator> uriValidators,
			List<AnchorValidator> anchorValidators) {

		if (resourceResolvers == null || uriValidators == null || anchorValidators == null) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}
		this.relativePathValidator = new RelativePathValidator(resourceResolvers.relativePathResolver());
		this.uriValidators = List.copyOf(uriValidators);
		this.anchorValidators = List.copyOf(anchorValidators);
	}

	@Override
	public Set<Class<? extends Node>> getTriggeringNodeTypes() {
		return TRIGGERING_NODE_TYPES;
	}

	@Override
	public CompletableFuture<List<ValidationIssue>> validate(Node node, MarkdownValidationContext context) {
		Document document = node.getDocument();
		ValidationIssueCollector issues = new ValidationIssueCollector();

		if (node instanceof Document) {
			checkLinkReferenceDefinitionIdentifiersAreUnique(document, issues);
		} else if (node instanceof Reference || node instanceof Paragraph) {
			findLinkReferenceDefinitionsIn(node).forEach(definition -> {
				checkLinkReferenceDefinitionIdentifier(definition, document, issues);
				checkLinkReferenceDefinitionTarget(definition, document, context, issues);
			});
		} else if (node instanceof Text) {
			checkLinksTheParserLeftAsText(node, document, context, issues);
		} else if (node instanceof Link || node instanceof Image) {
			findLinkOrImage(node)
					.ifPresent(link -> checkLinkOrImageTarget(link, document, context, issues));
		} else {
			findReferenceLink(node)
					.ifPresent(referenceLink -> checkReferenceLinkLabel(referenceLink, document, issues));
		}

		return issues.promised();
	}

	/**
	 * Checks the links the parser did not read as links, e.g. a reference link whose label it does
	 * not accept. Text nodes hold the plain text between the constructs the parser did read, so a
	 * link found here is not reported by one of the other checks a second time.
	 */
	private void checkLinksTheParserLeftAsText(Node node, Document document, MarkdownValidationContext context,
			ValidationIssueCollector issues) {
		BasedSequence sourceCode = document.getChars();
		int startOffset = node.getStartOffset();
		int endOffset = node.getEndOffset();

		MarkdownParsingTools.findLinksAndImages(sourceCode, startOffset, endOffset)
				.forEach(link -> checkLinkOrImageTarget(link, document, context, issues));

		Stream.concat(
				MarkdownParsingTools.findFullAndCollapsedReferenceLinks(sourceCode, startOffset, endOffset),
				MarkdownParsingTools.findShortcutReferenceLinks(sourceCode, startOffset, endOffset))
				.forEach(referenceLink -> checkReferenceLinkLabel(referenceLink, document, issues));
	}

	private static Optional<RegexMatch> findLinkOrImage(Node node) {
		return MarkdownParsingTools
				.findLinksAndImages(sourceCodeOf(node), node.getStartOffset(), node.getEndOffset())
				.filter(match -> describesTheWholeNode(match, node))
				.findFirst();
	}

	private static Optional<RegexMatch> findReferenceLink(Node node) {
		BasedSequence sourceCode = sourceCodeOf(node);
		int startOffset = node.getStartOffset();
		int endOffset = node.getEndOffset();

		Optional<RegexMatch> fullOrCollapsedLink = MarkdownParsingTools
				.findFullAndCollapsedReferenceLinks(sourceCode, startOffset, endOffset)
				.filter(match -> describesTheWholeNode(match, node))
				.findFirst();

		if (fullOrCollapsedLink.isPresent()) {
			return fullOrCollapsedLink;
		}

		return MarkdownParsingTools.findShortcutReferenceLinks(sourceCode, startOffset, endOffset)
				.filter(match -> describesTheWholeNode(match, node))
				.findFirst();
	}

	/**
	 * Returns the source code of the whole document the given node belongs to. Every offset of a
	 * node is an offset in that source code, so this is the only sequence the patterns may run on.
	 */
	private static BasedSequence sourceCodeOf(Node node) {
		return node.getDocument().getChars();
	}

	/**
	 * Tells whether the given match is the node itself and not something nested in it. The
	 * expressions for links do not know the exclamation mark of an image, so a match may start
	 * one character behind the node.
	 */
	private static boolean describesTheWholeNode(RegexMatch match, Node node) {
		int startOffset = node.getStartOffset();
		boolean image = node instanceof Image || node instanceof ImageRef;

		return match.startIndex == startOffset || (image && match.startIndex == startOffset + 1);
	}

	private static Stream<RegexMatch> findLinkReferenceDefinitionsIn(Node node) {
		return MarkdownParsingTools
				.findLinkReferenceDefinitions(sourceCodeOf(node), node.getStartOffset(), node.getEndOffset());
	}

	private void checkLinkReferenceDefinitionIdentifier(RegexMatch linkReferenceDefinition,
			Document document, ValidationIssueCollector issues) {

		RegexMatch labelMatch = linkReferenceDefinition.subMatches.get(MarkdownParsingTools.CAPTURING_GROUP_LABEL);
		if (labelMatch == null
				|| MarkdownParsingTools.isValidLinkReferenceDefinitionIdentifier(labelMatch.matchedText)) {
			return;
		}

		issues.add(new ValidationIssue(MarkdownIssueTypes.LINK_REFERENCE_DEFINITION_INVALID_IDENTIFIER,
				IssueSeverity.ERROR, invalidLinkReferenceDefinitionIdentifierMessage(labelMatch.matchedText),
				TextUtils.getLineNumberForOffset(document, labelMatch.startIndex),
				labelMatch.startIndex, labelMatch.endIndex));
	}

	private void checkLinkReferenceDefinitionIdentifiersAreUnique(Document document,
			ValidationIssueCollector issues) {

		Map<String, List<RegexMatch>> identifiers = new LinkedHashMap<>();
		collectLinkReferenceDefinitionIdentifiers(document, identifiers);

		identifiers.entrySet().stream()
				.filter(identifier -> identifier.getValue().size() > 1)
				.forEach(identifier -> {
					String lines = identifier.getValue().stream()
							.map(labelMatch -> TextUtils.getLineNumberForOffset(document,
									labelMatch.startIndex))
							.map(String::valueOf)
							.collect(Collectors.joining(", "));

					for (RegexMatch labelMatch : identifier.getValue()) {
						issues.add(new ValidationIssue(
								MarkdownIssueTypes.LINK_REFERENCE_DEFINITION_DUPLICATE_IDENTIFIER,
								IssueSeverity.ERROR,
								"The link reference definition identifier \"" + identifier.getKey()
										+ "\" is not unique."
										+ " The same identifier is used in the following lines: " + lines,
								TextUtils.getLineNumberForOffset(document, labelMatch.startIndex),
								labelMatch.startIndex, labelMatch.endIndex));
					}
				});
	}

	/**
	 * Collects the identifiers of all link reference definitions of the document, in document
	 * order. A definition belongs to the whole document, so a reference is defined no matter where
	 * in the document the definition stands.
	 */
	private static void collectLinkReferenceDefinitionIdentifiers(Node node,
			Map<String, List<RegexMatch>> identifiers) {

		for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
			if (child instanceof Reference || child instanceof Paragraph) {
				findLinkReferenceDefinitionsIn(child)
						.map(definition -> definition.subMatches.get(MarkdownParsingTools.CAPTURING_GROUP_LABEL))
						.filter(labelMatch -> labelMatch != null)
						.forEach(labelMatch -> identifiers
								.computeIfAbsent(labelMatch.matchedText, identifier -> new ArrayList<>(2))
								.add(labelMatch));
			} else {
				collectLinkReferenceDefinitionIdentifiers(child, identifiers);
			}
		}
	}

	private static String invalidLinkReferenceDefinitionIdentifierMessage(String identifier) {
		return "The link reference definition identifier \"" + identifier + "\" is invalid."
				+ " It has to contain at least one non-space character"
				+ " and is allowed to contain any number of the following characters:"
				+ " letters ([A-Za-z]), digits ([0-9]), hyphens (\"-\"), underscores (\"_\"),"
				+ " colons (\":\"), periods (\".\"), slashes (\"/\"), spaces (\" \").";
	}

	/**
	 * Checks the target of a link or an image, i.e. of a statement of the form
	 * <code>[label](target)</code> or <code>![label](target)</code>. A target that is not there
	 * leaves nothing to mark, so the brackets around it are marked instead.
	 */
	private void checkLinkOrImageTarget(RegexMatch linkOrImage, Document document,
			MarkdownValidationContext context, ValidationIssueCollector issues) {

		RegexMatch targetMatch = linkOrImage.subMatches.get(MarkdownParsingTools.CAPTURING_GROUP_TARGET);
		if (targetMatch == null) {
			return;
		}

		if (!targetMatch.matchedText.isBlank()) {
			checkTargetResource(targetMatch, document, context, issues);
			return;
		}

		int startOffset = targetMatch.startIndex;
		int endOffset = startOffset + targetMatch.matchedText.length();

		if (targetMatch.matchedText.isEmpty()) {
			// there is nothing to mark, so mark the brackets surrounding the target as well
			startOffset--;
			endOffset++;
		}

		reportEmptyTarget(document, startOffset, endOffset, issues);
	}

	/**
	 * Checks the target of a link reference definition, i.e. of a statement of the form
	 * <code>[label]: target</code>. It names a target like a link does, so the target itself is
	 * checked the same way, but a target that is not there leaves nothing to mark: the whole
	 * statement is marked instead of the brackets a link would have around its target.
	 */
	private void checkLinkReferenceDefinitionTarget(RegexMatch linkReferenceDefinition, Document document,
			MarkdownValidationContext context, ValidationIssueCollector issues) {

		RegexMatch targetMatch =
				linkReferenceDefinition.subMatches.get(MarkdownParsingTools.CAPTURING_GROUP_TARGET);
		if (targetMatch == null) {
			return;
		}

		if (!targetMatch.matchedText.isBlank()) {
			checkTargetResource(targetMatch, document, context, issues);
			return;
		}

		int startOffset = targetMatch.matchedText.isEmpty()
				? linkReferenceDefinition.startIndex
				: targetMatch.startIndex;

		reportEmptyTarget(document, startOffset,
				targetMatch.startIndex + targetMatch.matchedText.length(), issues);
	}

	private static void reportEmptyTarget(Document document, int startOffset, int endOffset,
			ValidationIssueCollector issues) {

		issues.add(new ValidationIssue(
				MarkdownIssueTypes.LINK_EMPTY_TARGET,
				IssueSeverity.ERROR,
				MESSAGE_EMPTY_LINK_TARGET,
				TextUtils.getLineNumberForOffset(document, startOffset),
				startOffset,
				endOffset));
	}

	/**
	 * Hands the resource a link points to over to the validator answering for the way the target
	 * is written. A target written as a path is meant as seen from the document carrying it, and
	 * is looked for; a target naming its resource on its own is reported, because it leads there
	 * on one machine only; everything else names a scheme and is checked by the validator
	 * answering for that scheme, e.g. a web address by one asking whether the address is there.
	 */
	private void checkTargetResource(RegexMatch targetMatch, Document document,
			MarkdownValidationContext context, ValidationIssueCollector issues) {

		String targetReference = targetMatch.matchedText;

		if (ResourceResolverRegistry.isRelativePath(targetReference)) {
			List<ValidationIssue> pathIssues = new ArrayList<>();
			Optional<Resource> targetResource =
					this.relativePathValidator.checkTargetResource(targetMatch, document, pathIssues);
			issues.addAll(pathIssues);
			checkTargetAnchor(targetMatch, document, targetResource, context, issues);
		} else if (ResourceResolverRegistry.isAbsolutePathWithoutScheme(targetReference)) {
			List<ValidationIssue> pathIssues = new ArrayList<>();
			this.absolutePathValidator.checkTargetPath(targetMatch, document, pathIssues);
			issues.addAll(pathIssues);
		} else {
			checkTargetUri(targetMatch, document, context, issues);
		}
	}

	/**
	 * Checks the place inside the target a link names, e.g. the <code>section</code> of
	 * <code>guide.md#section</code>.
	 * 
	 * <p>What the fragment names is looked for by the validator answering for the kind of target it
	 * stands in, this one only says where the fragment is written. A fragment with a path is looked
	 * for only where the target itself was found; a fragment without a path names a place in the
	 * document carrying the link, which is parsed already and is therefore handed over as it
	 * is.</p>
	 */
	private void checkTargetAnchor(RegexMatch targetMatch, Document document,
			Optional<Resource> targetResource, MarkdownValidationContext context,
			ValidationIssueCollector issues) {

		LinkTarget target = LinkTarget.of(targetMatch.matchedText);
		if (target.fragment() == null || target.fragment().isBlank()) {
			return;
		}

		int startOffset = targetMatch.startIndex + targetMatch.matchedText.indexOf('#');
		int endOffset = startOffset + 1 + target.fragment().length();
		int lineNumber = TextUtils.getLineNumberForOffset(document, startOffset);

		AnchorTarget anchorTarget;
		if (target.path() == null || target.path().isBlank()) {
			anchorTarget = AnchorTarget.inTheDocumentItself(target.fragment(), document, lineNumber,
					startOffset, endOffset);
		} else if (targetResource.isEmpty()) {
			// the target itself is not there, which was said already; where the target is, the
			// fragment cannot be looked for
			return;
		} else {
			anchorTarget = new AnchorTarget(target.path(), target.fragment(), targetResource.get(), document,
					lineNumber, startOffset, endOffset);
		}

		this.anchorValidators.stream()
				.filter(validator -> validator.isResponsibleFor(anchorTarget))
				.findFirst()
				.ifPresentOrElse(
						validator -> checkAnchorWith(validator, anchorTarget, targetMatch, document, context,
								issues),
						() -> issues.add(noAnchorValidatorIssue(anchorTarget)));
	}

	/**
	 * Reads the target before the validator answering for it is asked, so that a target which
	 * cannot be read is one finding in one place, whatever kind of target it is. What cannot be read
	 * is the file, so the path is marked and not the fragment. A fragment naming a place in the
	 * document carrying it reads nothing.
	 */
	private void checkAnchorWith(AnchorValidator validator, AnchorTarget target, RegexMatch targetMatch,
			Document document, MarkdownValidationContext context, ValidationIssueCollector issues) {

		if (!target.namesTheDocumentItself()) {
			try {
				context.getContents(target.targetResource());
			} catch (IOException failure) {
				issues.add(targetCannotBeReadIssue(target, targetMatch, document));
				return;
			}
		}

		issues.addPromised(validator.validate(target, context));
	}

	private static ValidationIssue noAnchorValidatorIssue(AnchorTarget target) {
		return new ValidationIssue(
				MarkdownIssueTypes.ANCHOR_NO_VALIDATOR_FOR_TARGET,
				IssueSeverity.WARNING,
				noAnchorValidatorMessage(target),
				target.lineNumber(),
				target.startOffset(),
				target.endOffset());
	}

	private static String noAnchorValidatorMessage(AnchorTarget target) {
		if (target.namesTheDocumentItself()) {
			return String.format("The anchor '%s' cannot be checked, because nothing answers for a"
					+ " Markdown document.", target.anchor());
		}
		return String.format("The anchor '%s' cannot be checked, because nothing answers for a target"
				+ " like '%s'.", target.anchor(), target.targetPath());
	}

	private static ValidationIssue targetCannotBeReadIssue(AnchorTarget target, RegexMatch targetMatch,
			Document document) {

		int startOffset = targetMatch.startIndex;
		int endOffset = targetMatch.startIndex + targetMatch.matchedText.indexOf('#');

		return new ValidationIssue(
				MarkdownIssueTypes.LINK_TARGET_CANNOT_BE_READ,
				IssueSeverity.ERROR,
				String.format("The referenced file '%s' cannot be read, so the anchor '%s' cannot be"
						+ " looked for. Resolved target path: %s",
						target.targetPath(), target.anchor(), target.targetResource().getResolvedPath()),
				TextUtils.getLineNumberForOffset(document, startOffset),
				startOffset,
				endOffset);
	}

	/**
	 * Hands a target naming a scheme to the first registered validator saying that it answers for
	 * it. A target no validator claims is left alone: nothing here knows what the scheme means,
	 * and a target nobody understands is not a target that is wrong.
	 */
	private void checkTargetUri(RegexMatch targetMatch, Document document,
			MarkdownValidationContext context, ValidationIssueCollector issues) {

		if (this.uriValidators.isEmpty()) {
			return;
		}

		int startOffset = targetMatch.startIndex;
		int endOffset = startOffset + targetMatch.matchedText.length();
		UriTarget target = UriTarget.of(targetMatch.matchedText,
				TextUtils.getLineNumberForOffset(document, startOffset), startOffset, endOffset);

		for (UriValidator validator : this.uriValidators) {
			if (validator.isResponsibleFor(target)) {
				issues.addPromised(validator.validate(target, context));
				return;
			}
		}
	}

	private void checkReferenceLinkLabel(RegexMatch referenceLink, Document document,
			ValidationIssueCollector issues) {

		RegexMatch targetMatch = referenceLink.subMatches.get(MarkdownParsingTools.CAPTURING_GROUP_TARGET);
		if (targetMatch == null) {
			return;
		}
		RegexMatch labelMatch = referenceLink.subMatches.get(MarkdownParsingTools.CAPTURING_GROUP_LABEL);

		// first, assume a full reference link like [link text][linkLabel]
		// or a shortcut reference link like [linkLabel]
		String linkLabel = targetMatch.matchedText;

		// in some rare cases we have a collapsed reference link like [linkLabel][]
		boolean collapsedReferenceLink = false;
		if (linkLabel.isEmpty() && labelMatch != null && !labelMatch.matchedText.isBlank()) {
			linkLabel = labelMatch.matchedText;
			collapsedReferenceLink = true;
		}

		if (linkLabel.isBlank()) {
			int startOffset = targetMatch.startIndex;
			int endOffset = startOffset + linkLabel.length();

			if (linkLabel.isEmpty()) {
				// there is nothing to mark, so mark the brackets surrounding the label as well
				startOffset--;
				endOffset++;
			}

			issues.add(new ValidationIssue(
					MarkdownIssueTypes.LINK_EMPTY_REFERENCE_LABEL,
					IssueSeverity.ERROR,
					MESSAGE_EMPTY_REFERENCE_LINK_LABEL,
					TextUtils.getLineNumberForOffset(document, startOffset),
					startOffset,
					endOffset));
			return;
		}

		if (MarkdownParsingTools.findLinkReferenceDefinition(document.getChars().toString(), linkLabel).isPresent()) {
			return;
		}

		if (collapsedReferenceLink) {
			// an empty full reference link looks like a collapsed reference link,
			// so we cannot tell which of the two problems it is:
			// full reference link:       [Some text][RefID]
			// collapsed reference link:  [RefID][]
			// empty full reference link: [Some text][]
			int startOffset = referenceLink.startIndex;
			int endOffset = startOffset + referenceLink.matchedText.length();

			issues.add(new ValidationIssue(
					MarkdownIssueTypes.LINK_AMBIGUOUS_REFERENCE,
					IssueSeverity.ERROR,
					ambiguousReferenceMessage(linkLabel),
					TextUtils.getLineNumberForOffset(document, startOffset),
					startOffset,
					endOffset));
		} else {
			int startOffset = targetMatch.startIndex;
			int endOffset = startOffset + linkLabel.length();

			issues.add(new ValidationIssue(
					MarkdownIssueTypes.LINK_MISSING_REFERENCE_DEFINITION,
					IssueSeverity.ERROR,
					missingReferenceDefinitionMessage(linkLabel),
					TextUtils.getLineNumberForOffset(document, startOffset),
					startOffset,
					endOffset));
		}
	}

	private static String missingReferenceDefinitionMessage(String linkLabel) {
		return "There is no link reference definition for the reference link label \"" + linkLabel
				+ "\". Expected a link reference definition like \"[ReferenceLinkLabel]: https://plantuml.com\"";
	}

	private static String ambiguousReferenceMessage(String linkLabel) {
		return "There is either no link reference definition for the reference link label \"" + linkLabel
				+ "\" (assuming this is a collapsed reference link like \"[ReferenceLinkLabel][]\")"
				+ " or the reference link label is empty (assuming this is a full reference link"
				+ " like \"[Some text][ReferenceLinkLabel]\")."
				+ " Expected a link reference definition like \"[" + linkLabel + "]: https://plantuml.com\""
				+ " or a reference link \"[" + linkLabel + "][ReferenceLinkLabel]\""
				+ " to an existing link reference definition.";
	}

}
