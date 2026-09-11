/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.advantest.markdown.service.validation.IssueSeverity;
import com.advantest.markdown.service.validation.MarkdownValidationContext;
import com.advantest.markdown.service.validation.ValidationIssue;
import com.advantest.markdown.service.validation.uri.UriTarget;
import com.advantest.markdown.service.validation.uri.UriValidator;

/**
 * Tests that a link target naming a scheme reaches the validator answering for it, and that no
 * other target does.
 */
class UriValidatorDispatchTest {

	private static final String ISSUE_TYPE = "test.uriTarget";

	/** Remembers every target it is asked about and reports each one it answers for. */
	private static final class RecordingUriValidator implements UriValidator {

		private final List<UriTarget> claimedTargets = new ArrayList<>();

		private final List<UriTarget> targetsAskedAbout = new ArrayList<>();

		private final String claimedScheme;

		private final String name;

		private RecordingUriValidator(String claimedScheme, String name) {
			this.claimedScheme = claimedScheme;
			this.name = name;
		}

		@Override
		public boolean isResponsibleFor(UriTarget target) {
			this.targetsAskedAbout.add(target);
			return target.scheme().filter(this.claimedScheme::equals).isPresent();
		}

		@Override
		public CompletableFuture<List<ValidationIssue>> validate(UriTarget target,
				MarkdownValidationContext context) {
			this.claimedTargets.add(target);
			return CompletableFuture.completedFuture(List.of(
					new ValidationIssue(ISSUE_TYPE, IssueSeverity.WARNING, this.name,
							target.lineNumber(), target.startOffset(), target.endOffset())));
		}
	}

	@Test
	void targetNamingASchemeReachesTheValidatorAnsweringForIt() {
		RecordingUriValidator webAddresses = new RecordingUriValidator("https", "web addresses");
		MarkdownService service = serviceWith(webAddresses);

		List<ValidationIssue> issues = service.validateMarkdown("[label](https://example.org/guide)");

		assertEquals(1, webAddresses.claimedTargets.size());
		UriTarget target = webAddresses.claimedTargets.get(0);
		assertEquals("https://example.org/guide", target.uriText());
		assertEquals(1, target.lineNumber());
		assertEquals(8, target.startOffset());
		assertEquals(33, target.endOffset());
		assertEquals(1, issues.size());
		assertEquals(ISSUE_TYPE, issues.get(0).issueTypeId());
	}

	@Test
	void targetOfAnImageReachesTheValidatorAsWell() {
		RecordingUriValidator webAddresses = new RecordingUriValidator("https", "web addresses");

		serviceWith(webAddresses).validateMarkdown("![label](https://example.org/picture.png)");

		assertEquals(1, webAddresses.claimedTargets.size());
		assertEquals("https://example.org/picture.png", webAddresses.claimedTargets.get(0).uriText());
	}

	@Test
	void targetOfALinkReferenceDefinitionReachesTheValidatorAsWell() {
		RecordingUriValidator webAddresses = new RecordingUriValidator("https", "web addresses");

		serviceWith(webAddresses).validateMarkdown("[key]: https://example.org/guide\n\nSee [key].");

		assertEquals(1, webAddresses.claimedTargets.size());
		assertEquals("https://example.org/guide", webAddresses.claimedTargets.get(0).uriText());
	}

	@Test
	void targetWrittenAsAPathIsNoneOfTheValidatorsBusiness() {
		RecordingUriValidator anyTarget = new RecordingUriValidator("https", "web addresses");

		serviceWith(anyTarget).validateMarkdown("[label](guide/introduction.md)");

		assertTrue(anyTarget.targetsAskedAbout.isEmpty());
	}

	@Test
	void targetNamingItsResourceOnItsOwnIsNoneOfTheValidatorsBusiness() {
		RecordingUriValidator anyTarget = new RecordingUriValidator("https", "web addresses");

		serviceWith(anyTarget).validateMarkdown("[label](/absolute/path/guide.md)");

		assertTrue(anyTarget.targetsAskedAbout.isEmpty());
	}

	@Test
	void targetNoValidatorClaimsIsLeftAlone() {
		RecordingUriValidator webAddresses = new RecordingUriValidator("https", "web addresses");

		List<ValidationIssue> issues =
				serviceWith(webAddresses).validateMarkdown("[label](mailto:someone@example.org)");

		assertEquals(1, webAddresses.targetsAskedAbout.size());
		assertTrue(webAddresses.claimedTargets.isEmpty());
		assertTrue(issues.isEmpty());
	}

	@Test
	void targetNamingASchemeIsLeftAloneWhereNoValidatorIsRegistered() {
		List<ValidationIssue> issues =
				new MarkdownService().validateMarkdown("[label](https://example.org/guide)");

		assertTrue(issues.isEmpty());
	}

	@Test
	void validatorAddedLastIsAskedFirst() {
		RecordingUriValidator allWebAddresses = new RecordingUriValidator("https", "all web addresses");
		RecordingUriValidator oneTeamsAddresses = new RecordingUriValidator("https", "one team's addresses");

		List<ValidationIssue> issues = MarkdownService.builder()
				.withUriValidator(allWebAddresses)
				.withUriValidator(oneTeamsAddresses)
				.build()
				.validateMarkdown("[label](https://example.org/guide)");

		assertEquals(1, issues.size());
		assertEquals("one team's addresses", issues.get(0).message());
		assertTrue(allWebAddresses.targetsAskedAbout.isEmpty());
	}

	@Test
	void validatorAddedEarlierAnswersForWhatTheLaterOneDoesNotClaim() {
		RecordingUriValidator webAddresses = new RecordingUriValidator("https", "web addresses");
		RecordingUriValidator mailAddresses = new RecordingUriValidator("mailto", "mail addresses");

		List<ValidationIssue> issues = MarkdownService.builder()
				.withUriValidator(webAddresses)
				.withUriValidator(mailAddresses)
				.build()
				.validateMarkdown("[label](https://example.org/guide)");

		assertEquals(1, issues.size());
		assertEquals("web addresses", issues.get(0).message());
		assertEquals(1, mailAddresses.targetsAskedAbout.size());
	}

	@Test
	void everyTargetOfADocumentIsCheckedWhereItStands() {
		RecordingUriValidator webAddresses = new RecordingUriValidator("https", "web addresses");

		List<ValidationIssue> issues = serviceWith(webAddresses).validateMarkdown(
				"See [one](https://example.org/one).\n\nAnd [two](https://example.org/two).");

		assertEquals(2, issues.size());
		assertEquals(1, issues.get(0).lineNumber());
		assertEquals(3, issues.get(1).lineNumber());
	}

	private static MarkdownService serviceWith(UriValidator validator) {
		return MarkdownService.builder().withUriValidator(validator).build();
	}

}
