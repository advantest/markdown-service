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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.advantest.markdown.service.validation.IssueSeverity;
import com.advantest.markdown.service.validation.MarkdownIssueTypes;
import com.advantest.markdown.service.validation.ValidationIssue;

/**
 * Tests what the shipped validator of web addresses answers for a target, and which target it
 * answers for at all.
 */
class DefaultHttpUriValidatorTest {

	/** Answers what a test tells it to and remembers which addresses it was asked about. */
	private static final class StubbedReachabilityChecker implements UriReachabilityChecker {

		private final List<URI> askedAddresses = new ArrayList<>();

		private final UriReachability answer;

		private StubbedReachabilityChecker(UriReachability answer) {
			this.answer = answer;
		}

		@Override
		public UriReachability check(URI uri) {
			this.askedAddresses.add(uri);
			return this.answer;
		}
	}

	private static final UriReachability REACHED = new UriReachability.Answered(200);

	@Test
	void validatorNeedsACheckToAskWith() {
		assertThrows(IllegalArgumentException.class, () -> new DefaultHttpUriValidator(null));
	}

	@Test
	void validatorAnswersForAWebAddress() {
		DefaultHttpUriValidator validator = validatorAnswering(REACHED);

		assertTrue(validator.isResponsibleFor(targetOf("https://example.org/guide")));
		assertTrue(validator.isResponsibleFor(targetOf("http://example.org/guide")));
	}

	@Test
	void validatorAnswersForAWebAddressWhateverItIsWrittenLike() {
		DefaultHttpUriValidator validator = validatorAnswering(REACHED);

		assertTrue(validator.isResponsibleFor(targetOf("HTTPS://Example.org/guide")));
	}

	@Test
	void validatorAnswersForATextThatOnlyLooksLikeAWebAddress() {
		DefaultHttpUriValidator validator = validatorAnswering(REACHED);

		assertTrue(validator.isResponsibleFor(targetOf("https:/example.org/guide")));
		assertTrue(validator.isResponsibleFor(targetOf("https://example.org/a guide")));
	}

	@Test
	void validatorLeavesEveryOtherSchemeAlone() {
		DefaultHttpUriValidator validator = validatorAnswering(REACHED);

		assertFalse(validator.isResponsibleFor(targetOf("mailto:someone@example.org")));
		assertFalse(validator.isResponsibleFor(targetOf("ftp://example.org/guide")));
		assertFalse(validator.isResponsibleFor(targetOf("guide/introduction.md")));
	}

	@Test
	void validatorRejectsATargetItIsNotGiven() {
		DefaultHttpUriValidator validator = validatorAnswering(REACHED);

		assertThrows(IllegalArgumentException.class, () -> validator.isResponsibleFor(null));
		assertThrows(IllegalArgumentException.class, () -> validator.validate(null));
	}

	@Test
	void addressNotBeginningWithAWebSchemeIsReported() {
		DefaultHttpUriValidator validator = validatorAnswering(REACHED);

		List<ValidationIssue> issues = validator.validate(targetOf("https:/example.org/guide"));

		assertEquals(1, issues.size());
		ValidationIssue issue = issues.get(0);
		assertEquals(MarkdownIssueTypes.LINK_INVALID_WEB_ADDRESS, issue.issueTypeId());
		assertEquals(IssueSeverity.ERROR, issue.severity());
		assertEquals("The referenced web address 'https:/example.org/guide' seems not to be a valid"
				+ " HTTP web address. It has to start with https:// or http://", issue.message());
	}

	@Test
	void addressNotBeginningWithAWebSchemeIsNotAskedAbout() {
		StubbedReachabilityChecker checker = new StubbedReachabilityChecker(REACHED);

		new DefaultHttpUriValidator(checker).validate(targetOf("https:/example.org/guide"));

		assertTrue(checker.askedAddresses.isEmpty());
	}

	@Test
	void addressThatCannotBeReadIsReportedWithTheReasonItCannotBeRead() {
		DefaultHttpUriValidator validator = validatorAnswering(REACHED);

		List<ValidationIssue> issues = validator.validate(targetOf("https://example.org/a guide"));

		assertEquals(1, issues.size());
		ValidationIssue issue = issues.get(0);
		assertEquals(MarkdownIssueTypes.LINK_INVALID_WEB_ADDRESS, issue.issueTypeId());
		assertEquals(IssueSeverity.ERROR, issue.severity());
		assertTrue(issue.message().startsWith("The referenced web address 'https://example.org/a guide'"
				+ " seems not to be a valid HTTP web address. "), issue.message());
		assertTrue(issue.message().contains("Illegal character in path"), issue.message());
	}

	@Test
	void addressThatCannotBeReadIsNotAskedAbout() {
		StubbedReachabilityChecker checker = new StubbedReachabilityChecker(REACHED);

		new DefaultHttpUriValidator(checker).validate(targetOf("https://example.org/a guide"));

		assertTrue(checker.askedAddresses.isEmpty());
	}

	@Test
	void addressThatDoesNotAnswerIsReportedAsAWarning() {
		DefaultHttpUriValidator validator =
				validatorAnswering(new UriReachability.NotReached("HTTP connect timed out"));

		List<ValidationIssue> issues = validator.validate(targetOf("https://plantxyzuml.com"));

		assertEquals(1, issues.size());
		ValidationIssue issue = issues.get(0);
		assertEquals(MarkdownIssueTypes.LINK_WEB_ADDRESS_DOES_NOT_ANSWER, issue.issueTypeId());
		assertEquals(IssueSeverity.WARNING, issue.severity());
		assertEquals("The referenced web address 'https://plantxyzuml.com' seems not to exist."
				+ " (Error message: HTTP connect timed out)", issue.message());
	}

	@Test
	void addressAnsweringThatThereIsNothingThereIsReported() {
		DefaultHttpUriValidator validator = validatorAnswering(new UriReachability.Answered(404));

		List<ValidationIssue> issues = validator.validate(targetOf("https://example.org/gone"));

		assertEquals(1, issues.size());
		ValidationIssue issue = issues.get(0);
		assertEquals(MarkdownIssueTypes.LINK_WEB_ADDRESS_NOT_REACHABLE, issue.issueTypeId());
		assertEquals(IssueSeverity.ERROR, issue.severity());
		assertEquals("The referenced web address 'https://example.org/gone' is not reachable"
				+ " (HTTP status code 404).", issue.message());
	}

	@Test
	void addressAnsweringThatItIsBrokenItselfIsReportedAsWell() {
		DefaultHttpUriValidator validator = validatorAnswering(new UriReachability.Answered(500));

		List<ValidationIssue> issues = validator.validate(targetOf("https://example.org/broken"));

		assertEquals(1, issues.size());
		assertEquals("The referenced web address 'https://example.org/broken' is not reachable"
				+ " (HTTP status code 500).", issues.get(0).message());
	}

	@Test
	void addressThatIsThereIsNotReported() {
		DefaultHttpUriValidator validator = validatorAnswering(new UriReachability.Answered(200));

		assertTrue(validator.validate(targetOf("https://example.org/guide")).isEmpty());
	}

	@Test
	void addressAnsweringWithARedirectOrAnythingBelowFourHundredIsNotReported() {
		assertTrue(validatorAnswering(new UriReachability.Answered(301))
				.validate(targetOf("https://example.org/moved")).isEmpty());
		assertTrue(validatorAnswering(new UriReachability.Answered(399))
				.validate(targetOf("https://example.org/odd")).isEmpty());
	}

	@Test
	void addressIsAskedAboutAsItIsWritten() {
		StubbedReachabilityChecker checker = new StubbedReachabilityChecker(REACHED);

		new DefaultHttpUriValidator(checker).validate(targetOf("https://example.org/guide?q=1#top"));

		assertEquals(List.of(URI.create("https://example.org/guide?q=1#top")), checker.askedAddresses);
	}

	@Test
	void reportedProblemMarksTheWholeAddressWhereItStands() {
		DefaultHttpUriValidator validator = validatorAnswering(new UriReachability.Answered(404));
		UriTarget target = UriTarget.of("https://example.org/gone", 7, 120, 144);

		ValidationIssue issue = validator.validate(target).get(0);

		assertEquals(7, issue.lineNumber());
		assertEquals(120, issue.startOffset());
		assertEquals(144, issue.endOffset());
	}

	@Test
	void addressWrittenWithoutTheSecureSchemeIsCheckedTheSameWay() {
		DefaultHttpUriValidator validator = validatorAnswering(new UriReachability.Answered(404));

		List<ValidationIssue> issues = validator.validate(targetOf("http://example.org/gone"));

		assertEquals(1, issues.size());
		assertEquals("The referenced web address 'http://example.org/gone' is not reachable"
				+ " (HTTP status code 404).", issues.get(0).message());
	}

	@Test
	void addressThatCannotBeReadIsRecognizedWhicheverSchemeItNames() {
		DefaultHttpUriValidator validator = validatorAnswering(REACHED);

		assertTrue(validator.isResponsibleFor(targetOf("http://example.org/a guide")));
		assertEquals(MarkdownIssueTypes.LINK_INVALID_WEB_ADDRESS,
				validator.validate(targetOf("http://example.org/a guide")).get(0).issueTypeId());
	}

	@Test
	void addressHandedOverAsNoneWithoutAReasonIsReportedWithoutOne() {
		DefaultHttpUriValidator validator = validatorAnswering(REACHED);
		UriTarget targetSayingItIsNoUri =
				new UriTarget("https://example.org/guide", Optional.empty(), 1, 0, 25);

		List<ValidationIssue> issues = validator.validate(targetSayingItIsNoUri);

		assertEquals(1, issues.size());
		assertEquals("The referenced web address 'https://example.org/guide' seems not to be a valid"
				+ " HTTP web address. ", issues.get(0).message());
	}

	private static DefaultHttpUriValidator validatorAnswering(UriReachability reachability) {
		return new DefaultHttpUriValidator(new StubbedReachabilityChecker(reachability));
	}

	private static UriTarget targetOf(String text) {
		return UriTarget.of(text, 1, 0, text.length());
	}

}
