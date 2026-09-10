/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.differential;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import com.advantest.markdown.service.validation.MarkdownIssueTypes;

/**
 * Recognizes the findings of a recorded FluentMark run that this service has a rule for.
 * 
 * <p>A recorded run reports more than this service does — the checks contributed by the proprietary
 * extensions above all. Only the findings of the rules that have been ported can be compared;
 * everything else would show as a difference forever and would hide the differences that
 * matter.</p>
 * 
 * <p>Which rules those are depends on the run: the rules about web addresses ask the network, so
 * they only count as ported where the run asks it as well, see
 * {@link #of(boolean) of(webAddressesAreChecked)}.</p>
 * 
 * <p>The recording cannot tell the rules apart on its own: every Markdown finding carries the same
 * marker type, because that is what Eclipse needs, not what a rule is. The message is therefore the
 * only thing left to recognize a rule by. That is bearable here, because a ported rule reproduces
 * the message of its origin exactly, and it fails loudly rather than silently — a message that
 * changes is no longer recognized, and the finding then shows up as missing.</p>
 */
final class PortedValidationRules {

	private record Rule(String issueTypeId, Predicate<String> recognizesMessage) {
	}

	private static final List<Rule> RULES = List.of(
			new Rule(MarkdownIssueTypes.LINK_EMPTY_TARGET,
					message -> message.startsWith("The target file path or URL is empty")),
			new Rule(MarkdownIssueTypes.LINK_EMPTY_REFERENCE_LABEL,
					message -> message.startsWith("The reference link label is empty")),
			new Rule(MarkdownIssueTypes.LINK_AMBIGUOUS_REFERENCE,
					message -> message.startsWith("There is either no link reference definition for")),
			new Rule(MarkdownIssueTypes.LINK_MISSING_REFERENCE_DEFINITION,
					message -> message.startsWith("There is no link reference definition for")),
			new Rule(MarkdownIssueTypes.LINK_REFERENCE_DEFINITION_INVALID_IDENTIFIER,
					message -> message.startsWith("The link reference definition identifier")
							&& message.contains("is invalid.")),
			new Rule(MarkdownIssueTypes.LINK_REFERENCE_DEFINITION_DUPLICATE_IDENTIFIER,
					message -> message.startsWith("The link reference definition identifier")
							&& message.contains("is not unique.")),
			new Rule(MarkdownIssueTypes.LINK_TARGET_DOES_NOT_EXIST,
					message -> message.startsWith("The referenced file or directory")
							&& message.contains("does not exist. Resolved target path:")),
			new Rule(MarkdownIssueTypes.LINK_FILE_PATH_WITH_TRAILING_SLASH,
					message -> message.startsWith("The file path")
							&& message.contains("ends with a '/'")),
			new Rule(MarkdownIssueTypes.LINK_DIRECTORY_PATH_WITHOUT_TRAILING_SLASH,
					message -> message.startsWith("The given path")
							&& message.contains("is a directory, not a file.")),
			new Rule(MarkdownIssueTypes.ANCHOR_INVALID_IDENTIFIER,
					message -> message.startsWith("The anchor identifier") && message.contains("is invalid.")),
			new Rule(MarkdownIssueTypes.ANCHOR_DUPLICATE_IDENTIFIER,
					message -> message.startsWith("The anchor identifier") && message.contains("is not unique.")),
			new Rule(MarkdownIssueTypes.ANCHOR_NOT_FOUND,
					message -> message.startsWith("There is no section with the anchor")
							&& message.contains("or the anchor is invalid.")));

	/**
	 * The rules asking the network, which are only ported into a comparison that asks it as well.
	 */
	private static final List<Rule> RULES_ASKING_THE_NETWORK = List.of(
			new Rule(MarkdownIssueTypes.LINK_INVALID_WEB_ADDRESS,
					message -> message.startsWith("The referenced web address")
							&& message.contains("seems not to be a valid HTTP web address.")),
			new Rule(MarkdownIssueTypes.LINK_WEB_ADDRESS_DOES_NOT_ANSWER,
					message -> message.startsWith("The referenced web address")
							&& message.contains("seems not to exist. (Error message:")),
			new Rule(MarkdownIssueTypes.LINK_WEB_ADDRESS_NOT_REACHABLE,
					message -> message.startsWith("The referenced web address")
							&& message.contains("is not reachable (HTTP status code")));

	private final List<Rule> rules;

	private PortedValidationRules(List<Rule> rules) {
		this.rules = rules;
	}

	/**
	 * Answers which rules a run compares by.
	 * 
	 * @param webAddressesAreChecked whether this run asks the network about a web address
	 * @return the rules of this service that the recording can be compared against
	 */
	static PortedValidationRules of(boolean webAddressesAreChecked) {
		if (!webAddressesAreChecked) {
			return new PortedValidationRules(RULES);
		}

		List<Rule> allRules = new ArrayList<>(RULES);
		allRules.addAll(RULES_ASKING_THE_NETWORK);

		return new PortedValidationRules(List.copyOf(allRules));
	}

	/**
	 * Answers which of this service's rules produced the given recorded message.
	 * 
	 * @param message a message of a recorded finding, must not be <code>null</code>
	 * @return the issue type identifier this service reports the same problem as, or an empty
	 *         optional if no rule of this service covers the finding yet
	 */
	Optional<String> issueTypeIdOf(String message) {
		return this.rules.stream()
				.filter(rule -> rule.recognizesMessage().test(message))
				.map(Rule::issueTypeId)
				.findFirst();
	}

}
