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

	private RewordedMessages() {
		// utility class, not meant to be instantiated
	}

	/**
	 * Applies every decided rewording to the given recorded message.
	 * 
	 * @param recordedMessage the message of a recorded finding, must not be <code>null</code>
	 * @return the message as this service words it, never <code>null</code>
	 */
	static String asThisServiceWordsIt(String recordedMessage) {
		// "Target path" leaves open which path is meant, the one written in the document or the one
		// it was resolved to; the message names the latter and now says so
		return recordedMessage.replace("does not exist. Target path:", "does not exist. Resolved target path:");
	}

}
