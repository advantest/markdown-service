/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.uri;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.markdown.service.validation.IssueSeverity;
import com.advantest.markdown.service.validation.MarkdownIssueTypes;
import com.advantest.markdown.service.validation.MarkdownValidationContext;
import com.advantest.markdown.service.validation.ValidationIssue;

/**
 * Tests which target the validator of what a web address looks like answers for, and what it says
 * about it.
 */
class HttpUriSyntaxValidatorTest {

	/** No validator of this test looks into another document, so what the context parses is never asked. */
	private static final MarkdownValidationContext CONTEXT =
			MarkdownValidationContext.parsingWith(new MarkdownParserAndHtmlRenderer());

	private final HttpUriSyntaxValidator validator = new HttpUriSyntaxValidator();

	@Test
	void validatorAnswersForATextThatOnlyLooksLikeAWebAddress() {
		assertTrue(this.validator.isResponsibleFor(targetOf("https:/example.org/guide")));
		assertTrue(this.validator.isResponsibleFor(targetOf("https://example.org/a guide")));
		assertTrue(this.validator.isResponsibleFor(targetOf("http://example.org/a guide")));
	}

	@Test
	void validatorAnswersWhateverTheSchemeIsWrittenLike() {
		assertTrue(this.validator.isResponsibleFor(targetOf("HTTPS:/Example.org/guide")));
	}

	@Test
	void validatorLeavesAWebAddressThatCanBeReadToWhoeverAsksIt() {
		assertFalse(this.validator.isResponsibleFor(targetOf("https://example.org/guide")));
		assertFalse(this.validator.isResponsibleFor(targetOf("http://example.org/guide")));
		assertFalse(this.validator.isResponsibleFor(targetOf("HTTPS://Example.org/guide")));
	}

	@Test
	void validatorLeavesEveryOtherSchemeAlone() {
		assertFalse(this.validator.isResponsibleFor(targetOf("mailto:someone@example.org")));
		assertFalse(this.validator.isResponsibleFor(targetOf("ftp://example.org/guide")));
		assertFalse(this.validator.isResponsibleFor(targetOf("guide/introduction.md")));
	}

	@Test
	void validatorRejectsATargetItIsNotGiven() {
		assertThrows(IllegalArgumentException.class, () -> this.validator.isResponsibleFor(null));
		assertThrows(IllegalArgumentException.class, () -> this.validator.validate(null, CONTEXT));
		assertThrows(IllegalArgumentException.class,
				() -> this.validator.validate(targetOf("https:/example.org"), null));
	}

	@Test
	void addressNotBeginningWithAWebSchemeIsReported() {
		List<ValidationIssue> issues =
				this.validator.validate(targetOf("https:/example.org/guide"), CONTEXT).join();

		assertEquals(1, issues.size());
		ValidationIssue issue = issues.get(0);
		assertEquals(MarkdownIssueTypes.LINK_HTTP_INVALID_WEB_ADDRESS, issue.issueTypeId());
		assertEquals(IssueSeverity.ERROR, issue.severity());
		assertEquals("The referenced web address 'https:/example.org/guide' seems not to be a valid"
				+ " HTTP web address. It has to start with https:// or http://", issue.message());
	}

	@Test
	void addressThatCannotBeReadIsReportedWithTheReasonItCannotBeRead() {
		List<ValidationIssue> issues =
				this.validator.validate(targetOf("https://example.org/a guide"), CONTEXT).join();

		assertEquals(1, issues.size());
		ValidationIssue issue = issues.get(0);
		assertEquals(MarkdownIssueTypes.LINK_HTTP_INVALID_WEB_ADDRESS, issue.issueTypeId());
		assertEquals(IssueSeverity.ERROR, issue.severity());
		assertTrue(issue.message().startsWith("The referenced web address 'https://example.org/a guide'"
				+ " seems not to be a valid HTTP web address. "), issue.message());
		assertTrue(issue.message().contains("Illegal character in path"), issue.message());
	}

	@Test
	void addressThatCannotBeReadIsRecognizedWhicheverSchemeItNames() {
		List<ValidationIssue> issues =
				this.validator.validate(targetOf("http://example.org/a guide"), CONTEXT).join();

		assertEquals(1, issues.size());
		assertEquals(MarkdownIssueTypes.LINK_HTTP_INVALID_WEB_ADDRESS, issues.get(0).issueTypeId());
	}

	@Test
	void addressHandedOverAsNoneWithoutAReasonIsReportedWithoutOne() {
		UriTarget targetSayingItIsNoUri =
				new UriTarget("https://example.org/guide", Optional.empty(), 1, 0, 25);

		List<ValidationIssue> issues = this.validator.validate(targetSayingItIsNoUri, CONTEXT).join();

		assertEquals(1, issues.size());
		assertEquals("The referenced web address 'https://example.org/guide' seems not to be a valid"
				+ " HTTP web address. ", issues.get(0).message());
	}

	@Test
	void reportedProblemMarksTheWholeAddressWhereItStands() {
		UriTarget target = UriTarget.of("https:/example.org/guide", 7, 120, 144);

		ValidationIssue issue = this.validator.validate(target, CONTEXT).join().get(0);

		assertEquals(7, issue.lineNumber());
		assertEquals(120, issue.startOffset());
		assertEquals(144, issue.endOffset());
	}

	@Test
	void addressThatCanBeReadIsNotReportedEvenWhereTheValidatorIsAskedAnyway() {
		assertTrue(this.validator.validate(targetOf("https://example.org/guide"), CONTEXT).join().isEmpty());
	}

	private static UriTarget targetOf(String text) {
		return UriTarget.of(text, 1, 0, text.length());
	}

}
