/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.resources.walk;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What one validation run holds for its filters, shared by all filters and all walks of the run
 * and let go of with the run.
 * 
 * <p>Whatever is asked or read here is asked or read once per run, however many walks and filters
 * the run has, and nothing is kept for the next run, so that every run sees the current state.</p>
 * 
 * <p>Can be used from several threads at once.</p>
 */
public final class ResourceFilterContext {

	private static final String GIT = "git";

	private final GitIgnoredPaths gitIgnoredPaths;

	private final Map<List<Path>, BlacklistedPaths> blacklists = new ConcurrentHashMap<>();

	/**
	 * Creates the context of one run, asking the <code>git</code> command found on the
	 * <code>PATH</code>.
	 */
	public ResourceFilterContext() {
		this(GIT);
	}

	/**
	 * Creates the context of one run, asking the given git command.
	 * 
	 * @param gitExecutable the name or path of the git command
	 */
	ResourceFilterContext(String gitExecutable) {
		this.gitIgnoredPaths = new GitIgnoredPaths(gitExecutable);
	}

	/**
	 * Answers what git ignores, asked once per repository for this run.
	 * 
	 * @return what git ignores, never <code>null</code>
	 */
	public GitIgnoredPaths gitIgnoredPaths() {
		return this.gitIgnoredPaths;
	}

	/**
	 * Answers the files named in the given blacklist file, read once for this run.
	 * 
	 * @param listFile the blacklist file, must not be <code>null</code>
	 * @param base the folder the entries are relative to, must not be <code>null</code>
	 * @return the files listed, none if the list could not be read, which is reported once per run
	 * @throws IllegalArgumentException if an argument is <code>null</code>
	 * @see BlacklistedPaths
	 */
	public BlacklistedPaths blacklistedPaths(Path listFile, Path base) {
		if (listFile == null || base == null) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}
		Path file = listFile.toAbsolutePath().normalize();
		Path folder = base.toAbsolutePath().normalize();
		return this.blacklists.computeIfAbsent(List.of(file, folder), key -> BlacklistedPaths.read(file, folder));
	}

}
