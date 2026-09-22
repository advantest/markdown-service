/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.anchor;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.advantest.markdown.MarkdownFileExtensions;
import com.advantest.markdown.service.validation.IssueSeverity;
import com.advantest.markdown.service.validation.MarkdownIssueTypes;
import com.advantest.markdown.service.validation.MarkdownValidationContext;
import com.advantest.markdown.service.validation.ValidationIssue;
import com.vladsch.flexmark.util.ast.Document;
/**
 * Checks that a link into a Markdown document points to a section that document declares.
 * 
 * <p>Which target this validator answers for is decided by the extension of the path as it is
 * written in the link, and what counts as Markdown is what the parser of the service accepts, so
 * that this question is answered in one place for the whole library. A link naming nothing but a
 * fragment points into the document it stands in, which is a Markdown document by the very fact
 * that it is being checked, so this validator answers for it as well.</p>
 * 
 * <p>Another document is asked of the validation run rather than read here, so a document fifty
 * links point into is read and parsed once. The document a link stands in is taken as it was handed
 * over, so that what is checked is the text a reader is looking at and not the text that was stored
 * last.</p>
 */
public class MarkdownSectionAnchorValidator implements AnchorValidator {

	private static final Logger LOG = LoggerFactory.getLogger(MarkdownSectionAnchorValidator.class);

	private final MarkdownFileExtensions markdownFileExtensions;

	/**
	 * Creates the validator, answering for the files the given extensions call Markdown.
	 * 
	 * @param markdownFileExtensions the extensions read as Markdown, must not be <code>null</code>
	 * @throws IllegalArgumentException if the given extensions are <code>null</code>
	 */
	public MarkdownSectionAnchorValidator(MarkdownFileExtensions markdownFileExtensions) {
		if (markdownFileExtensions == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		this.markdownFileExtensions = markdownFileExtensions;
	}

	@Override
	public boolean isResponsibleFor(AnchorTarget target) {
		if (target == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}

		return target.namesTheDocumentItself()
				|| this.markdownFileExtensions.isMarkdownFile(target.targetPath());
	}

	@Override
	public CompletableFuture<List<ValidationIssue>> validate(AnchorTarget target,
			MarkdownValidationContext context) {

		if (target == null || context == null) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}

		Document targetDocument;
		if (target.namesTheDocumentItself()) {
			targetDocument = target.documentContainingTheLink();
		} else {
			try {
				targetDocument = context.getParsedMarkdownDocument(target.targetResource());
			} catch (IOException failure) {
				// whoever asks has read the target before asking, so the target can only have
				// vanished between the two reads; that it cannot be read is said where it is read,
				// and repeating it here would blame the fragment for what is wrong with the file
				LOG.debug("The target {} vanished between being read and being parsed, so the"
						+ " anchor in it is left unchecked.", target.targetResource(), failure);
				return CompletableFuture.completedFuture(List.of());
			}
		}

		Set<String> declaredAnchors = MarkdownSectionAnchors.validAnchorsIn(targetDocument);
		if (declaredAnchors.contains(target.anchor())) {
			return CompletableFuture.completedFuture(List.of());
		}

		return CompletableFuture.completedFuture(List.of(new ValidationIssue(
				MarkdownIssueTypes.ANCHOR_NOT_FOUND,
				IssueSeverity.ERROR,
				anchorNotFoundMessage(target),
				target.lineNumber(),
				target.startOffset(),
				target.endOffset())));
	}

	private static String anchorNotFoundMessage(AnchorTarget target) {
		if (target.namesTheDocumentItself()) {
			return String.format("There is no section with the anchor '%s' in this document,"
					+ " or the anchor is invalid.", target.anchor());
		}
		return String.format("There is no section with the anchor '%s' in the Markdown document '%s',"
				+ " or the anchor is invalid.",
				target.anchor(), target.targetResource().getResolvedPath());
	}

}
