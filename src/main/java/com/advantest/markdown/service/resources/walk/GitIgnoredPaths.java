/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.resources.walk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What git ignores, asked once per git repository for as long as one validation run lives.
 * 
 * <p>The installed <code>git</code> command is asked, the first time a path of a repository is
 * asked about, for every file and every folder it ignores in the whole repository. Every rule git
 * applies is thus applied by git itself. A path is looked up in the answer afterwards, without
 * asking git again.</p>
 * 
 * <p>Whatever goes wrong makes this skip nothing rather than fail: a path outside every repository
 * is not ignored; if git cannot be started, nothing is ignored for as long as this lives, which is
 * reported once; if git fails for a repository, nothing is ignored in that repository, which is
 * reported once per repository.</p>
 * 
 * <p>Can be used from several threads at once.</p>
 */
public final class GitIgnoredPaths {

	private static final Logger LOG = LoggerFactory.getLogger(GitIgnoredPaths.class);

	private static final String GIT_FOLDER = ".git";

	private final String gitExecutable;

	private final Map<Path, Optional<Path>> repositoryRoots = new ConcurrentHashMap<>();

	private final Map<Path, Repository> repositories = new ConcurrentHashMap<>();

	private final AtomicBoolean gitMissing = new AtomicBoolean();

	/**
	 * Creates the answers of a run, asking the given git command.
	 * 
	 * @param gitExecutable the name or path of the git command, must not be <code>null</code>
	 */
	GitIgnoredPaths(String gitExecutable) {
		this.gitExecutable = gitExecutable;
	}

	/**
	 * Tells whether git ignores the given path, either itself or because a folder above it is
	 * ignored as a whole.
	 * 
	 * @param path the path, absolute and normalized, must not be <code>null</code>
	 * @param folder whether the path is a folder
	 * @return <code>true</code> if git ignores the path, <code>false</code> if it does not, if the
	 *         path is not in a git repository, or if git could not tell
	 */
	public boolean isIgnored(Path path, boolean folder) {
		Path parent = path.getParent();
		if (parent == null) {
			return false;
		}
		Optional<Path> root = repositoryRootOf(parent);
		if (root.isEmpty()) {
			return false;
		}
		Repository repository = this.repositories.computeIfAbsent(root.get(), Repository::new);
		return repository.ignores(path, folder);
	}

	/**
	 * Finds the root of the git repository the given folder lies in, the nearest folder holding a
	 * <code>.git</code> folder or file, remembering the answer for every folder on the way.
	 */
	private Optional<Path> repositoryRootOf(Path folder) {
		List<Path> asked = new ArrayList<>();
		Optional<Path> root = null;
		for (Path current = folder; current != null; current = current.getParent()) {
			root = this.repositoryRoots.get(current);
			if (root != null) {
				break;
			}
			asked.add(current);
			if (Files.exists(current.resolve(GIT_FOLDER), LinkOption.NOFOLLOW_LINKS)) {
				root = Optional.of(current);
				break;
			}
		}
		if (root == null) {
			root = Optional.empty();
		}
		for (Path current : asked) {
			this.repositoryRoots.put(current, root);
		}
		return root;
	}

	/** What git ignores in one repository, asked the first time a path of it is asked about. */
	private final class Repository {

		private final Path root;

		private Set<Path> ignoredFolders;

		private Set<Path> ignoredFiles;

		Repository(Path root) {
			this.root = root;
		}

		boolean ignores(Path path, boolean folder) {
			askGitOnce();
			if (!folder && this.ignoredFiles.contains(path)) {
				return true;
			}
			if (this.ignoredFolders.isEmpty()) {
				return false;
			}
			for (Path current = folder ? path : path.getParent(); current != null
					&& !current.equals(this.root); current = current.getParent()) {
				if (this.ignoredFolders.contains(current)) {
					return true;
				}
			}
			return false;
		}

		private synchronized void askGitOnce() {
			if (this.ignoredFiles != null) {
				return;
			}
			Set<Path> folders = new HashSet<>();
			Set<Path> files = new HashSet<>();
			for (String line : askGit()) {
				if (line.endsWith("/")) {
					folders.add(this.root.resolve(line.substring(0, line.length() - 1)).normalize());
				} else {
					files.add(this.root.resolve(line).normalize());
				}
			}
			this.ignoredFolders = folders;
			this.ignoredFiles = files;
		}

		private List<String> askGit() {
			if (GitIgnoredPaths.this.gitMissing.get()) {
				return List.of();
			}

			Process process;
			try {
				process = new ProcessBuilder(GitIgnoredPaths.this.gitExecutable, "-C", this.root.toString(),
						"ls-files", "-z", "--others", "--ignored", "--exclude-standard", "--directory").start();
			} catch (IOException e) {
				if (!GitIgnoredPaths.this.gitMissing.getAndSet(true)) {
					LOG.warn("Could not start git, so nothing git ignores is skipped: {}", e.getMessage());
				}
				return List.of();
			}

			StringBuilder errors = new StringBuilder();
			Thread errorReader = Thread.ofVirtual().start(() -> errors.append(readFully(process.getErrorStream())));
			try {
				String output = readFully(process.getInputStream());
				int exitCode = process.waitFor();
				errorReader.join();
				if (exitCode != 0) {
					LOG.warn("git failed with exit code {} in {}, so nothing git ignores is skipped there: {}",
							exitCode, this.root, errors.toString().strip());
					return List.of();
				}
				return splitAtZeroBytes(output);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				process.destroy();
				return List.of();
			}
		}
	}

	private static String readFully(InputStream stream) {
		try (stream) {
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return "";
		}
	}

	private static List<String> splitAtZeroBytes(String output) {
		List<String> lines = new ArrayList<>();
		for (String line : output.split("\0")) {
			if (!line.isEmpty()) {
				lines.add(line);
			}
		}
		return lines;
	}

}
