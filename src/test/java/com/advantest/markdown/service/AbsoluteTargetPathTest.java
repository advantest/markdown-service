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
 * Tests the rule about a target naming its resource on its own, e.g.
 * <code>/usr/share/doc/guide.md</code> or <code>C:\documents\guide.md</code>: such a target leads
 * to the resource on the machine the document was written on and nowhere else, so it is reported
 * rather than looked for.
 */
public class AbsoluteTargetPathTest {

	private final MarkdownService service = new MarkdownService();

	@TempDir
	private Path documentDirectory;

	@ParameterizedTest
	@ValueSource(strings = {
			"/usr/share/doc/guide.md",
			"/guide.md",
			"C:/documents/guide.md",
			"C:\\documents\\guide.md",
			"\\\\server\\share\\guide.md" })
	public void reportsATargetNamingItsResourceOnItsOwn(String linkTarget) {
		String markdown = "See the [guide](" + linkTarget + ") for details.\n";

		List<ValidationIssue> issues = this.service.validateMarkdown(markdown, documentResource());

		assertEquals(1, issues.size(), "Such a path is a mistake wherever it points: " + linkTarget);
		assertEquals(MarkdownIssueTypes.LINK_ABSOLUTE_TARGET_PATH, issues.get(0).issueTypeId());
	}

	@Test
	public void reportsWhereTheTargetStandsAndWhyItIsWrong() {
		String markdown = "See the [guide](/usr/share/doc/guide.md) for details.\n";
		int expectedStart = markdown.indexOf("/usr");

		assertEquals(
				List.of(new ValidationIssue(MarkdownIssueTypes.LINK_ABSOLUTE_TARGET_PATH,
						IssueSeverity.ERROR,
						"The path '/usr/share/doc/guide.md' names a file or directory of one machine,"
								+ " so it leads nowhere for anybody else reading this document."
								+ " Please use a path relative to this document instead.",
						1, expectedStart, expectedStart + "/usr/share/doc/guide.md".length())),
				this.service.validateMarkdown(markdown, documentResource()),
				"A reader is told what is wrong with the path and what to write instead.");
	}

	@Test
	public void reportsSuchATargetEvenWhereTheResourceIsThere() throws IOException {
		Path guide = this.documentDirectory.resolve("guide.md");
		Files.writeString(guide, "# Guide\n");

		String markdown = "See the [guide](" + guide.toString().replace('\\', '/') + ") for details.\n";

		List<ValidationIssue> issues = this.service.validateMarkdown(markdown, documentResource());

		assertEquals(1, issues.size(), "A target that works by coincidence is still the wrong target.");
		assertEquals(MarkdownIssueTypes.LINK_ABSOLUTE_TARGET_PATH, issues.get(0).issueTypeId());
	}

	@Test
	public void reportsSuchATargetOfAnImageAsWell() {
		String markdown = "![the logo](/pictures/logo.png)\n";

		List<ValidationIssue> issues = this.service.validateMarkdown(markdown, documentResource());

		assertEquals(1, issues.size(), "An image points to a resource like a link does.");
		assertEquals(MarkdownIssueTypes.LINK_ABSOLUTE_TARGET_PATH, issues.get(0).issueTypeId());
	}

	@Test
	public void reportsSuchATargetOfALinkReferenceDefinitionAsWell() {
		String markdown = "[guide]: /usr/share/doc/guide.md\n\nSee the [guide] for details.\n";

		List<ValidationIssue> issues = this.service.validateMarkdown(markdown, documentResource());

		assertEquals(1, issues.size(), "A link reference definition names a target like a link does.");
		assertEquals(MarkdownIssueTypes.LINK_ABSOLUTE_TARGET_PATH, issues.get(0).issueTypeId());
	}

	@Test
	public void reportsSuchATargetWithoutKnowingWhereTheDocumentIs() {
		String markdown = "See the [guide](/usr/share/doc/guide.md) for details.\n";

		List<ValidationIssue> issues = this.service.validateMarkdown(markdown);

		assertEquals(1, issues.size(), "Nothing has to be resolved to see that the path names one machine.");
		assertEquals(MarkdownIssueTypes.LINK_ABSOLUTE_TARGET_PATH, issues.get(0).issueTypeId());
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"file:///usr/share/doc/guide.md",
			"https://example.com/usr/share/doc/guide.md" })
	public void saysNothingAboutAPathNamedWithAScheme(String linkTarget) {
		String markdown = "See the [guide](" + linkTarget + ") for details.\n";

		assertTrue(this.service.validateMarkdown(markdown, documentResource()).isEmpty(),
				"A target naming a scheme is the business of whoever owns that scheme: " + linkTarget);
	}

	private Resource documentResource() {
		return LocalFileSystemResource.of(this.documentDirectory.resolve("document.md"));
	}

}
