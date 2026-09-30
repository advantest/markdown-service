/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.sourceforge.plantuml.security.SecurityProfile;

/**
 * Tests the settings of PlantUML this service passes on. They belong to the process rather than to
 * a service, so every test here puts back what it changed.
 */
class MarkdownServicePlantUmlSettingsTest {
	
	@AfterEach
	void leaveTheSettingsAsTheyWere() {
		MarkdownService.setDotExecutable(null);
		MarkdownService.setPlantUmlUrlAllowList(List.of());
	}
	
	private File anExecutableFile() throws IOException {
		File executable = File.createTempFile("dot-of-a-test", ".exe");
		executable.deleteOnExit();
		executable.setExecutable(true);
		return executable;
	}
	
	@Test
	void theExecutableThatWasNamedIsTheOneThatIsUsed() throws IOException {
		File executable = anExecutableFile();
		
		MarkdownService.setDotExecutable(executable.getAbsolutePath());
		
		assertEquals(Optional.of(executable.getAbsolutePath()), MarkdownService.getDotExecutable());
	}
	
	@Test
	void namingAPathThatHoldsNoExecutableSaysSo() {
		File missingExecutable = new File("this-directory-does-not-exist", "dot");
		
		assertThrows(IllegalArgumentException.class,
				() -> MarkdownService.setDotExecutable(missingExecutable.getPath()));
	}
	
	@Test
	void aServiceTakesTheUsualPlaceOfGraphvizWhereNobodyNamedOne() {
		MarkdownService.setDotExecutable(null);
		
		new MarkdownService();
		
		String usualPath = MarkdownService.usualDotExecutablePath();
		boolean graphvizIsInstalledThere = usualPath != null && new File(usualPath).isFile()
				&& new File(usualPath).canExecute();
		if (graphvizIsInstalledThere) {
			assertEquals(Optional.of(usualPath), MarkdownService.getDotExecutable());
		} else {
			assertEquals(Optional.empty(), MarkdownService.getDotExecutable());
		}
	}
	
	@Test
	void aServiceLeavesTheExecutableThatWasNamedAlone() throws IOException {
		File executable = anExecutableFile();
		MarkdownService.setDotExecutable(executable.getAbsolutePath());
		
		new MarkdownService();
		
		assertEquals(Optional.of(executable.getAbsolutePath()), MarkdownService.getDotExecutable());
	}
	
	@Test
	void theSecurityProfilePlantUmlAppliesIsTold() {
		SecurityProfile appliedProfile = MarkdownService.getPlantUmlSecurityProfile();
		
		assertNotNull(appliedProfile);
		
		MarkdownService.setPlantUmlSecurityProfile(appliedProfile);
		
		assertEquals(appliedProfile, MarkdownService.getPlantUmlSecurityProfile());
	}
	
	@Test
	void aSecurityProfileThatCannotBeAppliedAnyMoreIsSaidToBeOne() {
		SecurityProfile appliedProfile = MarkdownService.getPlantUmlSecurityProfile();
		SecurityProfile anotherProfile = appliedProfile == SecurityProfile.SANDBOX
				? SecurityProfile.INSECURE
				: SecurityProfile.SANDBOX;
		
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> MarkdownService.setPlantUmlSecurityProfile(anotherProfile));
		
		assertTrue(failure.getMessage().contains(appliedProfile.name()));
	}
	
	@Test
	void theAddressesThatWereAllowedAreTold() {
		MarkdownService.setPlantUmlUrlAllowList(List.of("https://one.example.com/"));
		
		assertEquals(List.of("https://one.example.com/"), MarkdownService.getPlantUmlUrlAllowList());
	}
	
}
