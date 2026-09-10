/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.differential;

/**
 * Rewrites a recorded message into the wording this service uses for the same finding.
 * 
 * <p>A message of the recorded run is compared with ours word by word, because a message is what a
 * reader gets to see. Where this service says the same thing in better words, the comparison would
 * otherwise fail for every finding of that rule and hide the differences that matter, so the
 * decided rewording is applied to the recorded message before comparing.</p>
 * 
 * <p>This is deliberately a short list, and it stays short: every entry is a decision that somebody
 * took, not a way of making a difference disappear. A rewording that is not listed here is a
 * failure.</p>
 */
final class RewordedMessages {

	private static final String ANCHOR_MISSING_PREFIX = "There is no section with the given anchor '";
	private static final String IN_THE_DOCUMENT = "' in the Markdown document '";
	private static final String ANCHOR_MISSING_SUFFIX = "' or the anchor is invalid.";

	private RewordedMessages() {
		// utility class, not meant to be instantiated
	}

	/**
	 * Applies every decided rewording to the message of the given recorded finding.
	 * 
	 * @param recordedFinding the finding as it was recorded, must not be <code>null</code>
	 * @return its message as this service words it, never <code>null</code>
	 */
	static String asThisServiceWordsIt(RecordedFinding recordedFinding) {
		// "Target path" leaves open which path is meant, the one written in the document or the one
		// it was resolved to; the message names the latter and now says so
		String message = recordedFinding.message()
				.replace("does not exist. Target path:", "does not exist. Resolved target path:")
				// two messages of the recorded run carry a double space where a sentence is put
				// together from two pieces; this service writes one, see I-01
				.replace("at least one non-space character  and", "at least one non-space character and")
				.replace("label is empty  (assuming", "label is empty (assuming");

		return anchorMessage(message, recordedFinding.file());
	}

	/**
	 * Rewords the message about an anchor no section carries.
	 * 
	 * <p>Two things are said differently. The word <em>given</em> says nothing a reader does not
	 * see anyway, and a document naming its own anchor is said to be "this document" rather than
	 * named by its path &mdash; a reader of the message is in that document already, and a path
	 * leading back to it tells him less than the two words do.</p>
	 * 
	 * @param message the recorded message with every other rewording applied
	 * @param recordedFile the validated file, relative to the corpus root, using forward slashes
	 * @return the message as this service words it
	 */
	private static String anchorMessage(String message, String recordedFile) {
		if (!message.startsWith(ANCHOR_MISSING_PREFIX) || !message.endsWith(ANCHOR_MISSING_SUFFIX)) {
			return message;
		}

		int documentStart = message.indexOf(IN_THE_DOCUMENT);
		if (documentStart < 0) {
			return message;
		}

		String anchor = message.substring(ANCHOR_MISSING_PREFIX.length(), documentStart);
		String document = message.substring(documentStart + IN_THE_DOCUMENT.length(),
				message.length() - ANCHOR_MISSING_SUFFIX.length());

		if (namesTheValidatedFile(document, recordedFile)) {
			return "There is no section with the anchor '" + anchor + "' in this document,"
					+ " or the anchor is invalid.";
		}

		return "There is no section with the anchor '" + anchor + "' in the Markdown document '"
				+ document + "', or the anchor is invalid.";
	}

	private static boolean namesTheValidatedFile(String document, String recordedFile) {
		return document.replace('\\', '/').endsWith("/" + recordedFile);
	}

}
