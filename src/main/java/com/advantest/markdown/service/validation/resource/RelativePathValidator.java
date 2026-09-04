/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.resource;

import com.advantest.markdown.service.validation.IssueSeverity;
import com.advantest.markdown.service.validation.MarkdownIssueTypes;
import com.advantest.markdown.service.validation.ValidationIssue;

import java.util.List;

import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.markdown.service.parsing.LinkTarget;
import com.advantest.markdown.service.parsing.RegexMatch;
import com.advantest.markdown.service.utils.TextUtils;
import com.advantest.resources.RelativePathResourceResolver;
import com.advantest.resources.Resource;
import com.advantest.resources.UnresolvedResource;
import com.vladsch.flexmark.util.ast.Document;

/**
 * Checks what a target written as a path points to: whether the resource is there, and whether the
 * path says what it points to.
 * 
 * <p>A path meant as seen from the document carrying it says nothing about where it is looked for.
 * Where that is, is the business of the resolver this validator was created with, and the rules
 * hold wherever the documents live &ndash; that a path ending with a slash announces a directory is
 * a convention of writing a path in prose and not of any one file system.</p>
 */
public class RelativePathValidator {

	private final RelativePathResourceResolver resourceResolver;

	/**
	 * Creates the validator, resolving what a path points to with the given resolver.
	 * 
	 * @param resourceResolver the resolver answering for the paths this validator is given, must
	 *                         not be <code>null</code>
	 */
	public RelativePathValidator(RelativePathResourceResolver resourceResolver) {
		if (resourceResolver == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		this.resourceResolver = resourceResolver;
	}

	/**
	 * Checks the resource the given target of a link, an image or a link reference definition
	 * points to.
	 * 
	 * <p>The fragment of a target names a place inside the target, e.g. a section of a document. It
	 * can only be looked for once the target itself is found, which is why only the resource is
	 * checked here.</p>
	 * 
	 * @param targetMatch the target as it stands in the document, written as a path and not as a
	 *                    URI, must not be <code>null</code>
	 * @param document the document the target is written in, must not be <code>null</code>
	 * @param issues the problems found so far, to which this validator adds its own, must not be
	 *               <code>null</code>
	 */
	public void checkTargetResource(RegexMatch targetMatch, Document document, List<ValidationIssue> issues) {
		String targetReference = targetMatch.matchedText;

		LinkTarget target = LinkTarget.of(targetReference);
		if (target.path() == null || target.path().isBlank()) {
			// the reference names a place inside the document it is written in, not a resource
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

		Resource targetResource = this.resourceResolver.resolve(targetReference, documentResource);
		if (!targetResource.exists()) {
			issues.add(new ValidationIssue(
					MarkdownIssueTypes.LINK_TARGET_DOES_NOT_EXIST,
					IssueSeverity.ERROR,
					missingTargetResourceMessage(target.path(), targetResource),
					lineNumber,
					startOffset,
					endOffset));
			return;
		}

		checkTargetPathTellsWhatItPointsTo(target, targetResource, lineNumber, startOffset, endOffset,
				issues);
	}

	/**
	 * Checks that the target path of a link says what it points to: a path ending with a slash
	 * announces a directory, a path without one a file. A target that keeps its promise is what a
	 * reader expects, and a target that does not is worth saying so even though it can be followed.
	 * 
	 * <p>The trailing slash is read from the target as it is written in the document, because a
	 * resolved path drops it &ndash; the file system tells apart a file from a directory by what is
	 * there, not by how the path was spelled.</p>
	 */
	private static void checkTargetPathTellsWhatItPointsTo(LinkTarget target, Resource targetResource,
			int lineNumber, int startOffset, int endOffset, List<ValidationIssue> issues) {

		boolean pathAnnouncesADirectory = target.path().endsWith("/");

		if (targetResource.isFile() && pathAnnouncesADirectory) {
			issues.add(new ValidationIssue(
					MarkdownIssueTypes.LINK_FILE_PATH_WITH_TRAILING_SLASH,
					IssueSeverity.ERROR,
					filePathWithTrailingSlashMessage(target.path()),
					lineNumber,
					startOffset,
					endOffset));
		} else if (targetResource.isDirectory() && !pathAnnouncesADirectory) {
			issues.add(new ValidationIssue(
					MarkdownIssueTypes.LINK_DIRECTORY_PATH_WITHOUT_TRAILING_SLASH,
					IssueSeverity.WARNING,
					directoryPathWithoutTrailingSlashMessage(target.path()),
					lineNumber,
					startOffset,
					endOffset));
		}
	}

	private static String filePathWithTrailingSlashMessage(String targetPath) {
		return String.format("The file path '%s' ends with a '/' which usually indicates a directory,"
				+ " not a file. Please remove the trailing '/' if you mean a file.",
				withoutCurrentDirectorySegments(targetPath));
	}

	private static String directoryPathWithoutTrailingSlashMessage(String targetPath) {
		return String.format("The given path '%s' is a directory, not a file."
				+ " Please add a trailing '/' if you really mean a directory.",
				withoutCurrentDirectorySegments(targetPath));
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

}
