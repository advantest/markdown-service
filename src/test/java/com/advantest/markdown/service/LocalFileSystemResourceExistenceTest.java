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
 * Tests the rule checking that the file or directory a link points to is there.
 * 
 * <p>A target is resolved relative to the document containing the link, so every test writes its
 * document into a temporary directory and validates it with that location. What a target without a
 * known document location yields is tested as well, because a caller is free to validate Markdown
 * source code that came from nowhere.</p>
 */
public class LocalFileSystemResourceExistenceTest {

	private final MarkdownService service = new MarkdownService();

	@TempDir
	private Path documentDirectory;

	@Test
	public void acceptsALinkToAnExistingFile() throws IOException {
		Files.writeString(this.documentDirectory.resolve("overview.md"), "# Overview\n");

		String markdown = "See the [overview](overview.md) for details.\n";

		assertTrue(this.service.validateMarkdown(markdown, documentResource()).isEmpty(),
				"A link to a file that is there must not be reported.");
	}

	@Test
	public void acceptsALinkToAnExistingDirectory() throws IOException {
		Files.createDirectory(this.documentDirectory.resolve("documents"));

		String markdown = "See the [documents](documents/) for details.\n";

		assertTrue(this.service.validateMarkdown(markdown, documentResource()).isEmpty(),
				"A link may point to a directory as well.");
	}

	/**
	 * A target is resolved against the directory of the document, so a document lying two levels
	 * below the temporary directory can reach a file next to that directory in several ways. Every
	 * one of them has to arrive at the same file.
	 */
	@ParameterizedTest
	@ValueSource(strings = {
			"../../target.txt",
			"./../../target.txt",
			"../.././target.txt",
			"../sub/../../target.txt",
			"../../some/dir/../../target.txt",
			"./../../some/path/../../target.txt" })
	public void acceptsALinkClimbingOutOfTheDocumentDirectory(String linkTarget) throws IOException {
		Files.writeString(this.documentDirectory.resolve("target.txt"), "content\n");
		Path deepDirectory = Files.createDirectories(this.documentDirectory.resolve("sub").resolve("deeper"));
		Files.createDirectory(this.documentDirectory.resolve("sub").resolve("dir"));

		String markdown = "See the [target](" + linkTarget + ") for details.\n";

		assertTrue(this.service.validateMarkdown(markdown, documentResourceIn(deepDirectory)).isEmpty(),
				"Every way of writing the path of that file leads to the same file: " + linkTarget);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"../missing.txt",
			"../../missing.txt",
			"./../../missing.txt",
			"../../some/path/to/../../other/dir/missing.txt",
			"../../../../../../../../../../missing.txt" })
	public void reportsALinkClimbingOutOfTheDocumentDirectoryToNowhere(String linkTarget) throws IOException {
		Path deepDirectory = Files.createDirectories(this.documentDirectory.resolve("sub").resolve("deeper"));

		String markdown = "See the [target](" + linkTarget + ") for details.\n";

		List<ValidationIssue> issues = this.service.validateMarkdown(markdown, documentResourceIn(deepDirectory));

		assertEquals(1, issues.size(), "The file is nowhere, however far the path climbs: " + linkTarget);
		assertEquals(MarkdownIssueTypes.LINK_TARGET_DOES_NOT_EXIST, issues.get(0).issueTypeId());
		assertEquals(markdown.indexOf(linkTarget), issues.get(0).startOffset(),
				"The marked range covers the target as it is written.");
		assertEquals(markdown.indexOf(linkTarget) + linkTarget.length(), issues.get(0).endOffset());
	}

	@Test
	public void namesTheResolvedPathOfATargetClimbingOutOfTheDocumentDirectory() throws IOException {
		Path deepDirectory = Files.createDirectories(this.documentDirectory.resolve("sub").resolve("deeper"));

		String markdown = "See the [target](../../some/dir/../missing.txt) for details.\n";

		String message = this.service.validateMarkdown(markdown, documentResourceIn(deepDirectory))
				.get(0).message();

		assertTrue(message.endsWith("Resolved target path: "
				+ this.documentDirectory.resolve("some").resolve("missing.txt").toString()),
				"The resolved path is the one that was looked for, with the climbing done: " + message);
	}

	@Test
	public void reportsALinkToAMissingFile() {
		String markdown = "See the [overview](documents/overview.md) for details.\n";

		int expectedStart = markdown.indexOf("documents/overview.md");
		String expectedTargetPath = this.documentDirectory.resolve("documents").resolve("overview.md").toString();

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_TARGET_DOES_NOT_EXIST, IssueSeverity.ERROR,
						"The referenced file or directory 'documents/overview.md' does not exist."
								+ " Resolved target path: " + expectedTargetPath,
						1, expectedStart, expectedStart + "documents/overview.md".length())),
				this.service.validateMarkdown(markdown, documentResource()),
				"The message names the target as it is written and where it was looked for.");
	}

	@Test
	public void reportsAnImageWithAMissingFile() {
		String markdown = "![A diagram](diagram.png)\n";

		int expectedStart = markdown.indexOf("diagram.png");

		assertEquals(1, this.service.validateMarkdown(markdown, documentResource()).size(),
				"Images are checked like links.");
		assertEquals(expectedStart,
				this.service.validateMarkdown(markdown, documentResource()).get(0).startOffset());
	}

	@Test
	public void namesTheTargetWithoutItsCurrentDirectorySegments() {
		String markdown = "See the [overview](./documents/./overview.md) for details.\n";

		String reportedMessage =
				this.service.validateMarkdown(markdown, documentResource()).get(0).message();

		assertTrue(reportedMessage.startsWith("The referenced file or directory 'documents/overview.md'"),
				"A segment saying \"this directory\" says nothing and is left out: " + reportedMessage);
	}

	@Test
	public void marksTheTargetWithoutItsFragment() {
		String markdown = "See the [overview](overview.md#a-section) for details.\n";

		int expectedStart = markdown.indexOf("overview.md#a-section");

		ValidationIssue issue = this.service.validateMarkdown(markdown, documentResource()).get(0);

		assertEquals(expectedStart + "overview.md".length(), issue.endOffset(),
				"The fragment names a place inside the target and is checked by another rule.");
	}

	@Test
	public void reportsThatTheDocumentLocationIsUnknown() {
		String markdown = "See the [overview](overview.md) for details.\n";

		int expectedStart = markdown.indexOf("overview.md");

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_UNKNOWN_DOCUMENT_LOCATION, IssueSeverity.ERROR,
						"The referenced file or directory 'overview.md' cannot be resolved,"
								+ " because the location of the document containing this link is unknown.",
						1, expectedStart, expectedStart + "overview.md".length())),
				this.service.validateMarkdown(markdown),
				"Without a document location a relative target cannot be resolved, and every link"
						+ " pointing somewhere says so.");
	}

	@Test
	public void acceptsAWebAddressWithoutLookingForAFile() {
		String markdown = "See the [overview](https://example.com/overview.md) for details.\n";

		assertTrue(this.service.validateMarkdown(markdown, documentResource()).isEmpty(),
				"A target with a scheme is resolved by whoever owns that scheme.");
	}

	@Test
	public void acceptsATargetOfNothingButAFragment() {
		String markdown = "See the [section](#a-section) for details.\n";

		assertTrue(this.service.validateMarkdown(markdown, documentResource()).isEmpty(),
				"A target pointing into the current document names no file to look for.");
	}

	@Test
	public void checksTheTargetOfALinkReferenceDefinition() {
		String markdown = "[overview]: overview.md\n\nSee the [overview] for details.\n";

		int expectedStart = markdown.indexOf("overview.md");

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_TARGET_DOES_NOT_EXIST, IssueSeverity.ERROR,
						"The referenced file or directory 'overview.md' does not exist. Resolved target path: "
								+ this.documentDirectory.resolve("overview.md").toString(),
						1, expectedStart, expectedStart + "overview.md".length())),
				this.service.validateMarkdown(markdown, documentResource()),
				"A link reference definition names a target as well.");
	}

	private Resource documentResource() {
		return documentResourceIn(this.documentDirectory);
	}

	private static Resource documentResourceIn(Path directory) {
		return LocalFileSystemResource.of(directory.resolve("document.md"));
	}

}
