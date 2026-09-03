/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.markdown.service.parsing.LinkTarget;
import com.advantest.markdown.service.parsing.MarkdownParsingTools;
import com.advantest.markdown.service.parsing.RegexMatch;
import com.advantest.markdown.service.utils.TextUtils;
import com.advantest.resources.Resource;
import com.advantest.resources.ResourceResolver;
import com.advantest.resources.UnresolvedResource;
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

	private final ResourceResolver resourceResolver;

	/**
	 * Creates the validator, resolving everything a link points to with the given resolver.
	 * 
	 * @param resourceResolver the resolver of the surrounding environment, must not be
	 *                         <code>null</code>
	 */
	MarkdownLinkValidator(ResourceResolver resourceResolver) {
		if (resourceResolver == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		this.resourceResolver = resourceResolver;
	}

	/**
	 * Returns the resolver answering where a link target is found. It is used by the rules about
	 * the files and directories a link points to.
	 * 
	 * @return the resolver, never <code>null</code>
	 */
	ResourceResolver getResourceResolver() {
		return this.resourceResolver;
	}

	@Override
	public Set<Class<? extends Node>> getTriggeringNodeTypes() {
		return TRIGGERING_NODE_TYPES;
	}

	@Override
	public List<ValidationIssue> validate(Node node) {
		Document document = node.getDocument();
		List<ValidationIssue> issues = new ArrayList<>();

		if (node instanceof Document) {
			checkLinkReferenceDefinitionIdentifiersAreUnique(document, issues);
		} else if (node instanceof Reference || node instanceof Paragraph) {
			findLinkReferenceDefinitionsIn(node).forEach(definition -> {
				checkLinkReferenceDefinitionIdentifier(definition, document, issues);
				checkLinkTarget(definition, document, true, issues);
			});
		} else if (node instanceof Text) {
			checkLinksTheParserLeftAsText(node, document, issues);
		} else if (node instanceof Link || node instanceof Image) {
			findLinkOrImage(node)
					.ifPresent(link -> checkLinkTarget(link, document, false, issues));
		} else {
			findReferenceLink(node)
					.ifPresent(referenceLink -> checkReferenceLinkLabel(referenceLink, document, issues));
		}

		return issues;
	}

	/**
	 * Checks the links the parser did not read as links, e.g. a reference link whose label it does
	 * not accept. Text nodes hold the plain text between the constructs the parser did read, so a
	 * link found here is not reported by one of the other checks a second time.
	 */
	private void checkLinksTheParserLeftAsText(Node node, Document document, List<ValidationIssue> issues) {
		BasedSequence sourceCode = document.getChars();
		int startOffset = node.getStartOffset();
		int endOffset = node.getEndOffset();

		MarkdownParsingTools.findLinksAndImages(sourceCode, startOffset, endOffset)
				.forEach(link -> checkLinkTarget(link, document, false, issues));

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
			Document document, List<ValidationIssue> issues) {

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
			List<ValidationIssue> issues) {

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
				// the double space is deliberate, it is part of the message this rule reports
				+ " It has to contain at least one non-space character "
				+ " and is allowed to contain any number of the following characters:"
				+ " letters ([A-Za-z]), digits ([0-9]), hyphens (\"-\"), underscores (\"_\"),"
				+ " colons (\":\"), periods (\".\"), slashes (\"/\"), spaces (\" \").";
	}

	private void checkLinkTarget(RegexMatch linkStatement, Document document,
			boolean targetInLinkReferenceDefinition, List<ValidationIssue> issues) {

		RegexMatch targetMatch = linkStatement.subMatches.get(MarkdownParsingTools.CAPTURING_GROUP_TARGET);
		if (targetMatch == null) {
			return;
		}

		String linkTarget = targetMatch.matchedText;
		if (!linkTarget.isBlank()) {
			checkTargetResourceExists(targetMatch, document, issues);
			return;
		}

		int startOffset = targetMatch.startIndex;
		int endOffset = startOffset + linkTarget.length();

		if (linkTarget.isEmpty()) {
			if (targetInLinkReferenceDefinition) {
				// there is nothing to mark, so mark the link reference definition statement instead
				startOffset = linkStatement.startIndex;
			} else {
				// there is nothing to mark, so mark the brackets surrounding the target as well
				startOffset--;
				endOffset++;
			}
		}

		issues.add(new ValidationIssue(
				MarkdownIssueTypes.LINK_EMPTY_TARGET,
				IssueSeverity.ERROR,
				MESSAGE_EMPTY_LINK_TARGET,
				TextUtils.getLineNumberForOffset(document, startOffset),
				startOffset,
				endOffset));
	}

	/**
	 * Checks that the resource a link points to is there. Only a target without a scheme names a
	 * resource of this environment; everything else is resolved by whoever owns its scheme, e.g. a
	 * web address by a browser, and is not this rule's business.
	 * 
	 * <p>The fragment of a target names a place inside the target, e.g. a section of a document. It
	 * can only be looked for once the target itself is found, which is why only the path is
	 * checked here.</p>
	 */
	private void checkTargetResourceExists(RegexMatch targetMatch, Document document,
			List<ValidationIssue> issues) {

		LinkTarget target = LinkTarget.of(targetMatch.matchedText);
		if (!target.isLocalResourcePath()) {
			return;
		}

		int startOffset = targetMatch.startIndex;
		int endOffset = startOffset + target.path().length();
		int lineNumber = TextUtils.getLineNumberForOffset(document, startOffset);

		Resource documentResource = MarkdownParserAndHtmlRenderer.getDocumentResource(document);
		if (UnresolvedResource.UNKNOWN_DOCUMENT.equals(documentResource)) {
			// a target is resolved relative to the document, so without knowing where the document
			// is there is nothing to look for
			issues.add(new ValidationIssue(
					MarkdownIssueTypes.LINK_UNKNOWN_DOCUMENT_LOCATION,
					IssueSeverity.ERROR,
					unknownDocumentLocationMessage(target.path()),
					lineNumber,
					startOffset,
					endOffset));
			return;
		}

		Resource targetResource = this.resourceResolver.resolve(target.path(), documentResource);
		if (targetResource.exists()) {
			return;
		}

		issues.add(new ValidationIssue(
				MarkdownIssueTypes.LINK_TARGET_DOES_NOT_EXIST,
				IssueSeverity.ERROR,
				missingTargetResourceMessage(target.path(), targetResource),
				lineNumber,
				startOffset,
				endOffset));
	}

	private static String missingTargetResourceMessage(String targetPath, Resource targetResource) {
		return String.format("The referenced file or directory '%s' does not exist. Resolved target path: %s",
				withoutCurrentDirectorySegments(targetPath), targetResource.getResolvedPath());
	}

	private static String unknownDocumentLocationMessage(String targetPath) {
		return String.format("The referenced file or directory '%s' cannot be resolved,"
				+ " because the location of the document containing this link is unknown.",
				withoutCurrentDirectorySegments(targetPath));
	}

	/**
	 * Drops the <code>./</code> segments of a path, which say "this directory" and therefore say
	 * nothing. A message names the target the way a reader would write it, while the offsets keep
	 * counting the target as it stands in the document.
	 */
	private static String withoutCurrentDirectorySegments(String targetPath) {
		String path = targetPath;

		while (path.startsWith("./")) {
			path = path.substring(2);
		}

		return path.replace("/./", "/");
	}

	private void checkReferenceLinkLabel(RegexMatch referenceLink, Document document,
			List<ValidationIssue> issues) {

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
				+ " or the reference link label is empty  (assuming this is a full reference link"
				+ " like \"[Some text][ReferenceLinkLabel]\")."
				+ " Expected a link reference definition like \"[" + linkLabel + "]: https://plantuml.com\""
				+ " or a reference link \"[" + linkLabel + "][ReferenceLinkLabel]\""
				+ " to an existing link reference definition.";
	}

}