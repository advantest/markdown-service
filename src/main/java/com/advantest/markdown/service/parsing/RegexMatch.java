/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2025 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.parsing;

import java.util.HashMap;
import java.util.Map;

public class RegexMatch {
	
	public final String matchedText;
	public final int startIndex;
	public final int endIndex;
	public final Map<String,RegexMatch> subMatches = new HashMap<>();
	
	public RegexMatch(String matchedText, int startIndex, int endIndex) {
		this.matchedText = matchedText;
		this.startIndex = startIndex;
		this.endIndex = endIndex;
	}
	
	public void addSubMatch(String matchName, RegexMatch subMatch) {
		if (matchName == null || matchName.isBlank() || subMatch == null
				|| subMatch.startIndex < startIndex || subMatch.endIndex > endIndex) {
			throw new IllegalArgumentException();
		}
		
		subMatches.put(matchName, subMatch);
	}
}