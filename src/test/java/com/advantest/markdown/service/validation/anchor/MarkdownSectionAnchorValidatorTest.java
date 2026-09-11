/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.anchor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.advantest.markdown.MarkdownFileExtensions;
import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.markdown.service.validation.IssueSeverity;
import com.advantest.markdown.service.validation.MarkdownIssueTypes;
import com.advantest.markdown.service.validation.MarkdownValidationContext;
import com.advantest.markdown.service.validation.ValidationIssue;
import com.advantest.resources.Resource;
import com.advantest.resources.ResourceKind;
import com.vladsch.flexmark.util.ast.Document;

/**
 * Checks the validator looking for a section anchor in the Markdown document a link points into.
 */
class MarkdownSectionAnchorValidatorTest {

	/** A resource answering with a fixed text. */
	private record TextResource(String resolvedPath, String contents) implements Resource {

		@Override
		public String getResolvedPath() {
			return this.resolvedPath;
		}

		@Override
		public boolean exists() {
			return true;
		}

		@Override
		public Optional<ResourceKind> getKind() {
			return Optional.of(ResourceKind.FILE);
		}

		@Override
		public BufferedReader readContents() {
			return new BufferedReader(new StringReader(this.contents));
		}
	}

	/** A resource nobody can read. */
	private record FailingResource(String resolvedPath) implements Resource {

		@Override
		public String getResolvedPath() {
			return this.resolvedPath;
		}

		@Override
		public boolean exists() {
			return true;
		}

		@Override
		public Optional<ResourceKind> getKind() {
			return Optional.of(ResourceKind.FILE);
		}

		@Override
		public BufferedReader readContents() throws IOException {
			throw new IOException("Nothing to read at " + this.resolvedPath + ".");
		}
	}

	private final MarkdownSectionAnchorValidator validator =
			new MarkdownSectionAnchorValidator(MarkdownFileExtensions.DEFAULT);

	private final MarkdownValidationContext context =
			MarkdownValidationContext.parsingWith(new MarkdownParserAndHtmlRenderer());

	@ParameterizedTest
	@ValueSource(strings = { "guide.md", "../docs/guide.md", "GUIDE.MD" })
	void answersForALinkIntoAMarkdownFile(String targetPath) {
		assertTrue(this.validator.isResponsibleFor(anchorTarget(targetPath, "section", "# Guide\n")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "guide.txt", "SomeClass.java", "guide.mkd", "images/diagram.svg" })
	void leavesALinkIntoAnythingElseToSomebodyElse(String targetPath) {
		assertFalse(this.validator.isResponsibleFor(anchorTarget(targetPath, "section", "# Guide\n")),
				"What is no Markdown file is read by whoever knows that kind of file.");
	}

	@Test
	void whatCountsAsMarkdownIsWhatItWasTold() {
		MarkdownSectionAnchorValidator validatorAcceptingMore =
				new MarkdownSectionAnchorValidator(MarkdownFileExtensions.accepting("md", "mkd"));

		assertTrue(validatorAcceptingMore.isResponsibleFor(anchorTarget("guide.mkd", "section", "# Guide\n")));
	}

	@Test
	void acceptsAnAnchorTheTargetDeclares() {
		AnchorTarget target = anchorTarget("guide.md", "installation",
				"# Guide {#guide}\n\n## Installation {#installation}\n");

		assertTrue(this.validator.validate(target, this.context).join().isEmpty(),
				"The target declares that anchor, so there is nothing to report.");
	}

	@Test
	void reportsAnAnchorTheTargetDoesNotDeclare() {
		AnchorTarget target = anchorTarget("guide.md", "installation", "# Guide {#guide}\n");

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.ANCHOR_NOT_FOUND, IssueSeverity.ERROR,
						"There is no section with the anchor 'installation' in the Markdown document"
								+ " '/docs/guide.md', or the anchor is invalid.",
						3, 20, 33)),
				this.validator.validate(target, this.context).join(),
				"A finding names the anchor that was looked for and where it was looked for it.");
	}

	@Test
	void reportsAnAnchorTheTargetDeclaresInvalidly() {
		AnchorTarget target = anchorTarget("guide.md", "1-guide", "# Guide {#1-guide}\n");

		List<ValidationIssue> issues = this.validator.validate(target, this.context).join();

		assertEquals(1, issues.size(), "A link to an invalid identifier does not lead anywhere.");
		assertEquals(MarkdownIssueTypes.ANCHOR_NOT_FOUND, issues.get(0).issueTypeId());
	}

	@Test
	void saysNothingAboutATargetItCouldNotRead() {
		Resource vanishedTarget = new FailingResource("/docs/guide.md");

		assertTrue(this.validator
				.validate(new AnchorTarget("guide.md", "section", vanishedTarget, parse("# Doc\n"), 3, 20, 28),
						this.context)
				.join().isEmpty(), "What is wrong with the file is said where the file is read.");
	}

	@Test
	void answersForAnAnchorNamingThePlaceInTheDocumentItself() {
		AnchorTarget target = AnchorTarget.inTheDocumentItself("section", parse("# Doc\n"), 3, 20, 28);

		assertTrue(this.validator.isResponsibleFor(target),
				"A document being checked as Markdown is a Markdown document.");
	}

	@Test
	void acceptsAnAnchorTheDocumentItselfDeclares() {
		Document document = parse("# Doc {#doc}\n\n[here](#doc)\n");
		AnchorTarget target = AnchorTarget.inTheDocumentItself("doc", document, 3, 20, 24);

		assertTrue(this.validator.validate(target, this.context).join().isEmpty(),
				"The document declares that anchor, so there is nothing to report.");
	}

	@Test
	void reportsAnAnchorTheDocumentItselfDoesNotDeclare() {
		Document document = parse("# Doc\n\n[here](#doc)\n");
		AnchorTarget target = AnchorTarget.inTheDocumentItself("doc", document, 3, 14, 18);

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.ANCHOR_NOT_FOUND, IssueSeverity.ERROR,
						"There is no section with the anchor 'doc' in this document,"
								+ " or the anchor is invalid.",
						3, 14, 18)),
				this.validator.validate(target, this.context).join(),
				"A finding about the document itself names no path, because there is none.");
	}

	@Test
	void readsTheDocumentItselfAsItWasHandedOver() {
		Resource storedDocument = new TextResource("/docs/guide.md", "# Doc\n");
		Document documentBeingEdited = parse("# Doc {#doc}\n\n[here](#doc)\n");

		assertTrue(this.validator
				.validate(AnchorTarget.inTheDocumentItself("doc", documentBeingEdited, 3, 20, 24),
						this.context)
				.join().isEmpty(),
				"What is checked is the text a reader is looking at, not what is stored: "
						+ storedDocument.getResolvedPath() + " is never read for it.");
	}

	@Test
	void nothingIsAnsweredAboutNoTargetAndNoRun() {
		AnchorTarget target = anchorTarget("guide.md", "section", "# Guide\n");

		assertThrows(IllegalArgumentException.class, () -> this.validator.isResponsibleFor(null));
		assertThrows(IllegalArgumentException.class, () -> this.validator.validate(null, this.context));
		assertThrows(IllegalArgumentException.class, () -> this.validator.validate(target, null));
	}

	@Test
	void aValidatorWithoutFileExtensionsCannotBeCreated() {
		assertThrows(IllegalArgumentException.class, () -> new MarkdownSectionAnchorValidator(null));
	}

	private static AnchorTarget anchorTarget(String targetPath, String anchor, String targetContents) {
		Resource target = new TextResource("/docs/" + targetPath, targetContents);
		int startOffset = 20;

		return new AnchorTarget(targetPath, anchor, target, parse("# Doc\n"), 3, startOffset,
				startOffset + 1 + anchor.length());
	}

	private static Document parse(String markdown) {
		return new MarkdownParserAndHtmlRenderer().parseMarkdown(markdown);
	}

}
