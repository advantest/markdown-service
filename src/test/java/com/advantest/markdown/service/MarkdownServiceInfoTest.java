/*
 * Copyright © 2026 Advantest Europe GmbH
 * SPDX-License-Identifier: BSD-3-Clause
 */
package com.advantest.markdown.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class MarkdownServiceInfoTest {
	
	@Test
	void readsTheVersionOfTheBuild() {
		String expected = System.getProperty("expectedVersion");
		assertNotNull(expected, "The build names the version it expects.");
		
		assertEquals(expected, MarkdownServiceInfo.getVersion());
		assertFalse(MarkdownServiceInfo.getVersion().contains("${"));
	}
	
	@Test
	void answersNoVersionWhereTheResourceIsMissing() {
		assertNull(MarkdownServiceInfo.readVersion("META-INF/com.advantest.markdown/no-such-library/version.properties"));
	}
	
	@Test
	void answersNoVersionWhereTheResourceNamesNone() {
		assertNull(MarkdownServiceInfo.readVersion("com/advantest/markdown/service/version-resources/no-version.properties"));
	}
	
	@Test
	void answersNoVersionWhereTheResourceWasNotFilledIn() {
		assertNull(MarkdownServiceInfo.readVersion("com/advantest/markdown/service/version-resources/unfiltered-version.properties"));
	}
	
}