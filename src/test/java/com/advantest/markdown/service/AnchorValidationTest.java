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
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.advantest.markdown.service.validation.IssueSeverity;
import com.advantest.markdown.service.validation.MarkdownIssueTypes;
import com.advantest.markdown.service.validation.MarkdownValidationContext;
import com.advantest.markdown.service.validation.ValidationIssue;
import com.advantest.markdown.service.validation.anchor.AnchorTarget;
import com.advantest.markdown.service.validation.anchor.AnchorValidator;
import com.advantest.resources.LocalFileSystemResource;
import com.advantest.resources.Resource;

/**
 * Tests the rules about what a link names inside its target, i.e. the <code>section</code> of
 * <code>guide.md#section</code>.
 * 
 * <p>A fragment is only looked for where the target itself was found, so every test writes its
 * document and everything it points at into a temporary directory.</p>
 */
public class AnchorValidationTest {

	/** A validator remembering what it was asked about and answering nothing. */
	private static final class RecordingAnchorValidator implements AnchorValidator {

		private final String extension;

		private final List<AnchorTarget> targetsAskedAbout = new ArrayList<>();

		private RecordingAnchorValidator(String extension) {
			this.extension = extension;
		}

		@Override
		public boolean isResponsibleFor(AnchorTarget target) {
			return target.targetPath().endsWith(this.extension);
		}

		@Override
		public List<ValidationIssue> validate(AnchorTarget target, MarkdownValidationContext context) {
			this.targetsAskedAbout.add(target);
			return List.of();
		}
	}

	private final MarkdownService service = new MarkdownService();

	@TempDir
	private Path documentDirectory;

	@Test
	public void acceptsAnAnchorTheDocumentItselfDeclares() {
		String markdown = "# Overview {#overview}\n\nBack to the [overview](#overview).\n";

		assertTrue(this.service.validateMarkdown(markdown, documentResource()).isEmpty(),
				"A link into the document carrying it finds what that document declares.");
	}

	@Test
	public void reportsAnAnchorTheDocumentItselfDoesNotDeclare() {
		String markdown = "# Overview {#overview}\n\nBack to the [summary](#summary).\n";

		int expectedStart = markdown.indexOf("#summary");

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.ANCHOR_NOT_FOUND, IssueSeverity.ERROR,
						"There is no section with the anchor 'summary' in this document,"
								+ " or the anchor is invalid.",
						3, expectedStart, expectedStart + "#summary".length())),
				this.service.validateMarkdown(markdown, documentResource()),
				"The fragment is marked, because the fragment is what is wrong.");
	}

	/**
	 * What a document carrying a link says is what is being checked, and that is not necessarily
	 * what is stored under its path &ndash; an editor asks about the text a reader is looking at.
	 */
	@Test
	public void checksAnAnchorOfTheDocumentItselfAgainstWhatIsBeingChecked() throws IOException {
		Files.writeString(this.documentDirectory.resolve("document.md"), "# Nothing here\n");

		String markdown = "# Overview {#overview}\n\nBack to the [overview](#overview).\n";

		assertTrue(this.service.validateMarkdown(markdown, documentResource()).isEmpty(),
				"The text handed in is checked, not the file lying under its path.");
	}

	@Test
	public void acceptsAnAnchorAnotherMarkdownDocumentDeclares() throws IOException {
		Files.writeString(this.documentDirectory.resolve("guide.md"),
				"# Guide {#guide}\n\n## Installation {#installation}\n");

		String markdown = "See the [installation](guide.md#installation) for details.\n";

		assertTrue(this.service.validateMarkdown(markdown, documentResource()).isEmpty(),
				"A link into another document finds what that document declares.");
	}

	@Test
	public void reportsAnAnchorAnotherMarkdownDocumentDoesNotDeclare() throws IOException {
		Path guide = this.documentDirectory.resolve("guide.md");
		Files.writeString(guide, "# Guide {#guide}\n");

		String markdown = "See the [installation](guide.md#installation) for details.\n";
		int expectedStart = markdown.indexOf("#installation");

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.ANCHOR_NOT_FOUND, IssueSeverity.ERROR,
						"There is no section with the anchor 'installation' in the Markdown document '"
								+ guide.toString() + "', or the anchor is invalid.",
						1, expectedStart, expectedStart + "#installation".length())),
				this.service.validateMarkdown(markdown, documentResource()),
				"A finding names the document the anchor was looked for in.");
	}

	@Test
	public void saysNothingAboutTheAnchorOfATargetThatIsNowhere() {
		String markdown = "See the [installation](guide.md#installation) for details.\n";

		List<ValidationIssue> issues = this.service.validateMarkdown(markdown, documentResource());

		assertEquals(1, issues.size(), "Where the file is missing, the anchor is not looked for.");
		assertEquals(MarkdownIssueTypes.LINK_FILES_TARGET_DOES_NOT_EXIST, issues.get(0).issueTypeId());
	}

	@Test
	public void reportsAnAnchorNobodyAnswersFor() throws IOException {
		Files.writeString(this.documentDirectory.resolve("SomeClass.java"), "class SomeClass {}\n");

		String markdown = "See [the method](SomeClass.java#doSomething) for details.\n";
		int expectedStart = markdown.indexOf("#doSomething");

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.ANCHOR_NO_VALIDATOR_FOR_TARGET,
						IssueSeverity.WARNING,
						"The anchor 'doSomething' cannot be checked, because nothing answers for a target"
								+ " like 'SomeClass.java'.",
						1, expectedStart, expectedStart + "#doSomething".length())),
				this.service.validateMarkdown(markdown, documentResource()),
				"That nothing was checked is worth saying, because nothing would ever look at it.");
	}

	@Test
	public void asksARegisteredValidatorAboutTheTargetItAnswersFor() throws IOException {
		Files.writeString(this.documentDirectory.resolve("SomeClass.java"), "class SomeClass {}\n");
		RecordingAnchorValidator javaValidator = new RecordingAnchorValidator(".java");
		MarkdownService serviceKnowingJava =
				MarkdownService.builder().withAnchorValidator(javaValidator).build();

		String markdown = "See [the method](SomeClass.java#doSomething) for details.\n";

		assertTrue(serviceKnowingJava.validateMarkdown(markdown, documentResource()).isEmpty(),
				"The validator answering for that target said nothing is wrong.");
		assertEquals(1, javaValidator.targetsAskedAbout.size());
		assertEquals("doSomething", javaValidator.targetsAskedAbout.get(0).anchor());
		assertEquals("SomeClass.java", javaValidator.targetsAskedAbout.get(0).targetPath());
	}

	/**
	 * A caller knowing a kind of file better than this library is asked first, so that it can
	 * answer about a Markdown file as well.
	 */
	@Test
	public void asksARegisteredValidatorBeforeTheOneShipped() throws IOException {
		Files.writeString(this.documentDirectory.resolve("guide.md"), "# Guide {#guide}\n");
		RecordingAnchorValidator markdownValidator = new RecordingAnchorValidator(".md");
		MarkdownService serviceWithItsOwnRule =
				MarkdownService.builder().withAnchorValidator(markdownValidator).build();

		String markdown = "See the [installation](guide.md#installation) for details.\n";

		assertTrue(serviceWithItsOwnRule.validateMarkdown(markdown, documentResource()).isEmpty(),
				"The registered validator answered alone, and it said nothing is wrong.");
		assertEquals(1, markdownValidator.targetsAskedAbout.size());
	}

	@Test
	public void reportsATargetThatCannotBeRead() throws IOException {
		// a directory carrying the name of a Markdown file is there, but nothing can read it as one;
		// that it is a directory is reported by another rule and is not what this test is about
		Path unreadableTarget = Files.createDirectory(this.documentDirectory.resolve("guide.md"));

		String markdown = "See the [installation](guide.md#installation) for details.\n";
		int expectedStart = markdown.indexOf("guide.md#");

		List<ValidationIssue> issues = this.service.validateMarkdown(markdown, documentResource());

		assertTrue(
				issues.contains(new ValidationIssue(MarkdownIssueTypes.LINK_TARGET_CANNOT_BE_READ,
						IssueSeverity.ERROR,
						"The referenced file 'guide.md' cannot be read, so the anchor 'installation' cannot"
								+ " be looked for. Resolved target path: " + unreadableTarget.toString(),
						1, expectedStart, expectedStart + "guide.md".length())),
				"What cannot be read is the file, so the path is marked and not the fragment. Reported: "
						+ issues);
	}

	@Test
	public void saysNothingAboutAFragmentOfAWebAddress() {
		String markdown = "See the [installation](https://example.com/guide#installation).\n";

		assertTrue(this.service.validateMarkdown(markdown, documentResource()).isEmpty(),
				"Whoever owns the scheme of that target decides what its fragment means.");
	}

	private Resource documentResource() {
		return LocalFileSystemResource.of(this.documentDirectory.resolve("document.md"));
	}

}
