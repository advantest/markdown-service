/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.advantest.markdown.service.validation.IssueSeverity;
import com.advantest.markdown.service.validation.MarkdownIssueTypes;
import com.advantest.markdown.service.validation.ValidationIssue;
import com.advantest.resources.LocalFileSystemResource;
import com.advantest.resources.Resource;

/**
 * Tests the two rules checking that a link target says what it points to: a path ending with a
 * slash announces a directory, a path without one a file.
 * 
 * <p>Both rules only speak about a target that is there — a target that is nowhere is reported by
 * the existence rule instead, and what its path announces is then beside the point.</p>
 */
public class LocalFileSystemPathShapeTest {

	private final MarkdownService service = new MarkdownService();

	@TempDir
	private Path documentDirectory;

	@ParameterizedTest
	@ValueSource(strings = {
			"overview.md",
			"./overview.md",
			"documents/../overview.md" })
	public void acceptsAFilePathWithoutATrailingSlash(String linkTarget) throws IOException {
		Files.writeString(this.documentDirectory.resolve("overview.md"), "# Overview\n");
		Files.createDirectory(this.documentDirectory.resolve("documents"));

		String markdown = "See the [overview](" + linkTarget + ") for details.\n";

		assertTrue(this.service.validateMarkdown(markdown, documentResource()).isEmpty(),
				"The path of a file says that it is a file: " + linkTarget);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"documents/",
			"./documents/",
			"documents/inner/../" })
	public void acceptsADirectoryPathWithATrailingSlash(String linkTarget) throws IOException {
		Files.createDirectories(this.documentDirectory.resolve("documents").resolve("inner"));

		String markdown = "See the [documents](" + linkTarget + ") for details.\n";

		assertTrue(this.service.validateMarkdown(markdown, documentResource()).isEmpty(),
				"The path of a directory says that it is a directory: " + linkTarget);
	}

	@Test
	public void reportsAFilePathEndingWithASlash() throws IOException {
		Files.writeString(this.documentDirectory.resolve("overview.md"), "# Overview\n");

		String markdown = "See the [overview](overview.md/) for details.\n";
		int expectedStart = markdown.indexOf("overview.md/");

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_FILE_PATH_WITH_TRAILING_SLASH,
						IssueSeverity.ERROR,
						"The file path 'overview.md/' ends with a '/' which usually indicates a directory,"
								+ " not a file. Please remove the trailing '/' if you mean a file.",
						1, expectedStart, expectedStart + "overview.md/".length())),
				this.service.validateMarkdown(markdown, documentResource()),
				"A trailing slash announces a directory, and this target is a file.");
	}

	@Test
	public void reportsADirectoryPathWithoutATrailingSlash() throws IOException {
		Files.createDirectory(this.documentDirectory.resolve("documents"));

		String markdown = "See the [documents](documents) for details.\n";
		int expectedStart = markdown.indexOf("documents)") ;

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_DIRECTORY_PATH_WITHOUT_TRAILING_SLASH,
						IssueSeverity.WARNING,
						"The given path 'documents' is a directory, not a file."
								+ " Please add a trailing '/' if you really mean a directory.",
						1, expectedStart, expectedStart + "documents".length())),
				this.service.validateMarkdown(markdown, documentResource()),
				"A path without a trailing slash reads like the path of a file,"
						+ " which is worth a warning rather than an error: the link can be followed.");
	}

	@Test
	public void namesTheTargetWithoutItsCurrentDirectorySegments() throws IOException {
		Files.createDirectory(this.documentDirectory.resolve("documents"));

		String markdown = "See the [documents](./documents) for details.\n";

		assertTrue(this.service.validateMarkdown(markdown, documentResource()).get(0).message()
						.startsWith("The given path 'documents' is a directory"),
				"A message names the target the way a reader would write it.");
	}

	@Test
	public void reportsTheShapeOfAnImageTargetAsWell() throws IOException {
		Files.createDirectory(this.documentDirectory.resolve("pictures"));

		String markdown = "![the pictures](pictures)\n";

		List<ValidationIssue> issues = this.service.validateMarkdown(markdown, documentResource());

		assertEquals(1, issues.size(), "An image points to a resource like a link does.");
		assertEquals(MarkdownIssueTypes.LINK_DIRECTORY_PATH_WITHOUT_TRAILING_SLASH,
				issues.get(0).issueTypeId());
	}

	@Test
	public void reportsTheShapeOfALinkReferenceDefinitionTargetAsWell() throws IOException {
		Files.createDirectory(this.documentDirectory.resolve("documents"));

		String markdown = "[documents]: documents\n\nSee the [documents] for details.\n";

		List<ValidationIssue> issues = this.service.validateMarkdown(markdown, documentResource());

		assertEquals(1, issues.size(), "A link reference definition names a target like a link does.");
		assertEquals(MarkdownIssueTypes.LINK_DIRECTORY_PATH_WITHOUT_TRAILING_SLASH,
				issues.get(0).issueTypeId());
	}

	@Test
	public void saysNothingAboutTheShapeOfATargetThatIsNowhere() {
		String markdown = "See the [documents](documents/) for details.\n";

		List<ValidationIssue> issues = this.service.validateMarkdown(markdown, documentResource());

		assertEquals(1, issues.size(), "A target that is nowhere is reported once, by the existence rule.");
		assertEquals(MarkdownIssueTypes.LINK_TARGET_DOES_NOT_EXIST, issues.get(0).issueTypeId());
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"https://example.com/documents",
			"https://example.com/documents/",
			"mailto:someone@example.com",
			"#a-section-of-this-document" })
	public void saysNothingAboutATargetItDoesNotResolve(String linkTarget) {
		String markdown = "See the [something](" + linkTarget + ") for details.\n";

		assertTrue(this.service.validateMarkdown(markdown, documentResource()).isEmpty(),
				"Whoever owns the scheme of that target decides what its shape means: " + linkTarget);
	}

	@Test
	public void saysNothingAboutTheShapeOfATargetOfADocumentThatIsNowhere() throws IOException {
		Files.createDirectory(this.documentDirectory.resolve("documents"));

		String markdown = "See the [documents](documents) for details.\n";

		List<ValidationIssue> issues = this.service.validateMarkdown(markdown);

		assertEquals(1, issues.size(), "Without a document location nothing is resolved, so nothing is known"
				+ " about the shape of the target.");
		assertEquals(MarkdownIssueTypes.LINK_UNKNOWN_DOCUMENT_LOCATION, issues.get(0).issueTypeId());
	}

	private Resource documentResource() {
		return LocalFileSystemResource.of(this.documentDirectory.resolve("document.md"));
	}

}
