/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.resources.walk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The filter deciding which folders a walk enters and which Markdown files it validates, built
 * from the generic rules this library offers and the caller's own filters.
 * 
 * <p>The filter is immutable. It is built once, e.g. for a service, and adapted per run or per
 * walk with {@link #toBuilder()}:</p>
 * 
 * <pre>
 * DefaultMarkdownValidationResourcesFilter javaProjects = serviceFilter.toBuilder()
 *         .skippingPaths("/target/", "/bin/")
 *         .build();
 * </pre>
 * 
 * <p>A file or folder is skipped as soon as one rule skips it. The patterns given to one call are
 * alternatives, so a path is skipped by <code>skippingPaths("a", "b")</code> if it matches one of
 * them, and kept by <code>onlyPaths("a", "b")</code> if it matches one of them. Rules given by
 * several calls apply all, so two calls of {@link Builder#onlyPaths(String...)} keep what matches
 * both. The cheap rules are asked first, the lookups in what git ignores last.</p>
 * 
 * <p>Paths are matched by {@link Builder#onlyPaths(String...) GLOB patterns} relative to a base
 * folder, the walk's root unless a base is named, the same way on every operating system.</p>
 */
public final class DefaultMarkdownValidationResourcesFilter implements ResourceFilter {

	private final Builder settings;

	private final List<ResourceFilter> rules;

	private DefaultMarkdownValidationResourcesFilter(Builder settings, List<ResourceFilter> rules) {
		this.settings = settings;
		this.rules = rules;
	}

	/**
	 * Creates a builder with the defaults: symbolic links are skipped, i.e. not followed, and
	 * nothing else is.
	 * 
	 * @return a new builder, never <code>null</code>
	 */
	public static Builder builderWithDefaults() {
		return new Builder().skippingSymbolicLinks(true);
	}

	/**
	 * Creates a builder without any rule, so that a filter built from it skips nothing and follows
	 * symbolic links.
	 * 
	 * @return a new builder, never <code>null</code>
	 */
	public static Builder emptyBuilder() {
		return new Builder();
	}

	/**
	 * Creates a builder holding the rules of this filter, to build an adapted filter from.
	 * 
	 * @return a new builder, never <code>null</code>
	 */
	public Builder toBuilder() {
		return new Builder(this.settings);
	}

	@Override
	public boolean skipsFolder(Path root, Path folder, BasicFileAttributes attributes) {
		for (ResourceFilter rule : this.rules) {
			if (rule.skipsFolder(root, folder, attributes)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean skipsFile(Path root, Path file, BasicFileAttributes attributes) {
		for (ResourceFilter rule : this.rules) {
			if (rule.skipsFile(root, file, attributes)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public ResourceFilter createForRun(ResourceFilterContext context) {
		List<ResourceFilter> rulesForRun = new ArrayList<>(this.rules.size());
		boolean changed = false;
		for (ResourceFilter rule : this.rules) {
			ResourceFilter ruleForRun = rule.createForRun(context);
			changed |= ruleForRun != rule;
			rulesForRun.add(ruleForRun);
		}
		return changed ? new DefaultMarkdownValidationResourcesFilter(this.settings, List.copyOf(rulesForRun)) : this;
	}

	/** Builds a {@link DefaultMarkdownValidationResourcesFilter}; not to be used by several threads. */
	public static final class Builder {

		private boolean skipSymbolicLinks;

		private boolean skipDotPrefixedFolders;

		private boolean skipHidden;

		private boolean skipGitIgnored;

		private final List<ResourceFilter> extensionRules;

		private final List<ResourceFilter> pathRules;

		private final List<ResourceFilter> blacklistRules;

		private final List<ResourceFilter> ownRules;

		private Builder() {
			this.extensionRules = new ArrayList<>();
			this.pathRules = new ArrayList<>();
			this.blacklistRules = new ArrayList<>();
			this.ownRules = new ArrayList<>();
		}

		private Builder(Builder other) {
			this.skipSymbolicLinks = other.skipSymbolicLinks;
			this.skipDotPrefixedFolders = other.skipDotPrefixedFolders;
			this.skipHidden = other.skipHidden;
			this.skipGitIgnored = other.skipGitIgnored;
			this.extensionRules = new ArrayList<>(other.extensionRules);
			this.pathRules = new ArrayList<>(other.pathRules);
			this.blacklistRules = new ArrayList<>(other.blacklistRules);
			this.ownRules = new ArrayList<>(other.ownRules);
		}

		/**
		 * Sets whether symbolic links to files and folders are skipped, i.e. not followed.
		 * 
		 * @param skip <code>true</code> to skip them
		 * @return this builder
		 */
		public Builder skippingSymbolicLinks(boolean skip) {
			this.skipSymbolicLinks = skip;
			return this;
		}

		/**
		 * Sets whether folders whose name starts with <code>.</code> are skipped.
		 * 
		 * @param skip <code>true</code> to skip them
		 * @return this builder
		 */
		public Builder skippingDotPrefixedFolders(boolean skip) {
			this.skipDotPrefixedFolders = skip;
			return this;
		}

		/**
		 * Sets whether files and folders the file system marks hidden are skipped, which on Linux
		 * are those whose name starts with <code>.</code>. A file or folder whose attributes cannot
		 * be read is taken as not hidden.
		 * 
		 * @param skip <code>true</code> to skip them
		 * @return this builder
		 */
		public Builder skippingHidden(boolean skip) {
			this.skipHidden = skip;
			return this;
		}

		/**
		 * Sets whether files and folders the installed <code>git</code> command ignores, and every
		 * folder named <code>.git</code>, are skipped. git is asked once per repository and run.
		 * Where git cannot be asked, nothing is skipped for that reason, which is reported once.
		 * 
		 * @param skip <code>true</code> to skip them
		 * @return this builder
		 * @see GitIgnoredPaths
		 */
		public Builder skippingWhatGitIgnores(boolean skip) {
			this.skipGitIgnored = skip;
			return this;
		}

		/**
		 * Skips every file whose extension is none of the given ones, compared case-insensitively.
		 * A file without an extension is skipped.
		 * 
		 * @param extensions the extensions kept, with or without a leading <code>.</code>, e.g.
		 *                   <code>md</code>, must not be empty
		 * @return this builder
		 * @throws IllegalArgumentException if no extension is given, or one is <code>null</code> or
		 *                                  empty
		 */
		public Builder onlyFileExtensions(String... extensions) {
			Set<String> kept = normalizedExtensions(extensions);
			this.extensionRules.add(new FileExtensionRule(kept, true));
			return this;
		}

		/**
		 * Skips every file whose extension is one of the given ones, compared case-insensitively.
		 * 
		 * @param extensions the extensions skipped, with or without a leading <code>.</code>, e.g.
		 *                   <code>class</code>, must not be empty
		 * @return this builder
		 * @throws IllegalArgumentException if no extension is given, or one is <code>null</code> or
		 *                                  empty
		 */
		public Builder skippingFileExtensions(String... extensions) {
			Set<String> skipped = normalizedExtensions(extensions);
			this.extensionRules.add(new FileExtensionRule(skipped, false));
			return this;
		}

		/**
		 * Skips every file and folder not matching one of the given GLOB patterns, relative to the
		 * walk's root. A folder is entered if it matches, if a folder above it matches, or if
		 * something below it could match; a file is validated if it or a folder above it matches.
		 * 
		 * <p>The syntax follows the one of <code>.gitignore</code> files:</p>
		 * <ul>
		 * <li>A pattern without <code>/</code>, e.g. <code>*.md</code>, matches a name at any
		 * depth.</li>
		 * <li>A pattern with <code>/</code> at its start or inside, e.g. <code>/target</code> or
		 * <code>doc/images</code>, is anchored at the base folder.</li>
		 * <li>A pattern ending with <code>/</code> matches folders only.</li>
		 * <li>A segment <code>**</code> matches any number of folders; <code>doc/**</code> matches
		 * everything inside <code>doc</code>.</li>
		 * <li><code>*</code>, <code>?</code>, <code>[abc]</code>, <code>[a-z]</code>,
		 * <code>[!abc]</code> and <code>{a,b}</code> match inside a name; <code>\</code> takes the
		 * next character as it is.</li>
		 * </ul>
		 * <p>Names are compared case-sensitively, and the separator is <code>/</code> on every
		 * operating system.</p>
		 * 
		 * @param globs the patterns, of which a path must match one, must not be empty
		 * @return this builder
		 * @throws IllegalArgumentException if no pattern is given, or one is <code>null</code> or
		 *                                  malformed
		 */
		public Builder onlyPaths(String... globs) {
			this.pathRules.add(new PathRule(null, compile(globs), true));
			return this;
		}

		/**
		 * Skips every file and folder matching one of the given GLOB patterns, relative to the
		 * walk's root, with the syntax of {@link #onlyPaths(String...)}.
		 * 
		 * @param globs the patterns, of which a skipped path matches one, must not be empty
		 * @return this builder
		 * @throws IllegalArgumentException if no pattern is given, or one is <code>null</code> or
		 *                                  malformed
		 */
		public Builder skippingPaths(String... globs) {
			this.pathRules.add(new PathRule(null, compile(globs), false));
			return this;
		}

		/**
		 * Does what {@link #onlyPaths(String...)} does, with the patterns relative to the given
		 * base folder instead of the walk's root. A file or folder outside the base is skipped,
		 * except a folder the base lies in.
		 * 
		 * @param base the folder the patterns are relative to, must not be <code>null</code>
		 * @param globs the patterns, of which a path must match one, must not be empty
		 * @return this builder
		 * @throws IllegalArgumentException if the base is <code>null</code>, no pattern is given, or
		 *                                  one is <code>null</code> or malformed
		 */
		public Builder onlyPathsBelow(Path base, String... globs) {
			this.pathRules.add(new PathRule(absolute(base), compile(globs), true));
			return this;
		}

		/**
		 * Does what {@link #skippingPaths(String...)} does, with the patterns relative to the given
		 * base folder instead of the walk's root. Nothing outside the base is skipped.
		 * 
		 * @param base the folder the patterns are relative to, must not be <code>null</code>
		 * @param globs the patterns, of which a skipped path matches one, must not be empty
		 * @return this builder
		 * @throws IllegalArgumentException if the base is <code>null</code>, no pattern is given, or
		 *                                  one is <code>null</code> or malformed
		 */
		public Builder skippingPathsBelow(Path base, String... globs) {
			this.pathRules.add(new PathRule(absolute(base), compile(globs), false));
			return this;
		}

		/**
		 * Skips the files named in the given blacklist file, which is read once per validation
		 * run. If it cannot be read, no file is skipped for that reason, which is reported once
		 * per run.
		 * 
		 * @param listFile the blacklist file, must not be <code>null</code>
		 * @param base the folder the entries are relative to, must not be <code>null</code>
		 * @return this builder
		 * @throws IllegalArgumentException if an argument is <code>null</code>
		 * @see BlacklistedPaths
		 */
		public Builder skippingBlacklistedPaths(Path listFile, Path base) {
			if (listFile == null) {
				throw new IllegalArgumentException("The blacklist file must not be null.");
			}
			this.blacklistRules.add(new BlacklistRule(absolute(listFile), null, absolute(base), null));
			return this;
		}

		/**
		 * Skips the files named in the given lines of a blacklist, which were read already. The
		 * lines are resolved once per validation run.
		 * 
		 * @param lines the blacklist's lines, must not be <code>null</code>
		 * @param base the folder the entries are relative to, must not be <code>null</code>
		 * @return this builder
		 * @throws IllegalArgumentException if an argument is <code>null</code> or a line is
		 *                                  <code>null</code>
		 * @see BlacklistedPaths
		 */
		public Builder skippingBlacklistedPaths(List<String> lines, Path base) {
			if (lines == null || lines.stream().anyMatch(line -> line == null)) {
				throw new IllegalArgumentException("The blacklist's lines must not be null.");
			}
			this.blacklistRules.add(new BlacklistRule(null, List.copyOf(lines), absolute(base), null));
			return this;
		}

		/**
		 * Skips whatever the given filter skips. The filter is asked after the rules of this
		 * library, and {@link ResourceFilter#createForRun(ResourceFilterContext) created} for every
		 * run.
		 * 
		 * @param filter the caller's filter, must not be <code>null</code>
		 * @return this builder
		 * @throws IllegalArgumentException if the filter is <code>null</code>
		 */
		public Builder skipping(ResourceFilter filter) {
			if (filter == null) {
				throw new IllegalArgumentException("The filter must not be null.");
			}
			this.ownRules.add(filter);
			return this;
		}

		/**
		 * Builds the filter.
		 * 
		 * @return a new filter, never <code>null</code>
		 */
		public DefaultMarkdownValidationResourcesFilter build() {
			List<ResourceFilter> rules = new ArrayList<>();
			if (this.skipSymbolicLinks) {
				rules.add(SymbolicLinkRule.INSTANCE);
			}
			if (this.skipDotPrefixedFolders) {
				rules.add(DotPrefixedFolderRule.INSTANCE);
			}
			rules.addAll(this.extensionRules);
			rules.addAll(this.pathRules);
			if (this.skipHidden) {
				rules.add(HiddenRule.INSTANCE);
			}
			rules.addAll(this.blacklistRules);
			if (this.skipGitIgnored) {
				rules.add(GitIgnoreRule.UNBOUND);
			}
			rules.addAll(this.ownRules);
			return new DefaultMarkdownValidationResourcesFilter(new Builder(this), List.copyOf(rules));
		}

		private static Path absolute(Path path) {
			if (path == null) {
				throw new IllegalArgumentException("The base folder must not be null.");
			}
			return path.toAbsolutePath().normalize();
		}

		private static List<PathPattern> compile(String... globs) {
			if (globs == null || globs.length == 0) {
				throw new IllegalArgumentException("At least one path pattern must be given.");
			}
			return Stream.of(globs).map(PathPattern::compile).toList();
		}

		private static Set<String> normalizedExtensions(String... extensions) {
			if (extensions == null || extensions.length == 0) {
				throw new IllegalArgumentException("At least one file extension must be given.");
			}
			return Stream.of(extensions).map(extension -> {
				String normalized = extension == null ? "" : extension.strip();
				if (normalized.startsWith(".")) {
					normalized = normalized.substring(1);
				}
				if (normalized.isEmpty()) {
					throw new IllegalArgumentException("A file extension must neither be null nor empty.");
				}
				return normalized.toLowerCase(Locale.ROOT);
			}).collect(Collectors.toUnmodifiableSet());
		}
	}

	private enum SymbolicLinkRule implements ResourceFilter {
		INSTANCE;

		@Override
		public boolean skipsFolder(Path root, Path folder, BasicFileAttributes attributes) {
			return attributes.isSymbolicLink();
		}

		@Override
		public boolean skipsFile(Path root, Path file, BasicFileAttributes attributes) {
			return attributes.isSymbolicLink();
		}
	}

	private enum DotPrefixedFolderRule implements ResourceFilter {
		INSTANCE;

		@Override
		public boolean skipsFolder(Path root, Path folder, BasicFileAttributes attributes) {
			return folder.getFileName().toString().startsWith(".");
		}
	}

	private enum HiddenRule implements ResourceFilter {
		INSTANCE;

		@Override
		public boolean skipsFolder(Path root, Path folder, BasicFileAttributes attributes) {
			return isHidden(folder);
		}

		@Override
		public boolean skipsFile(Path root, Path file, BasicFileAttributes attributes) {
			return isHidden(file);
		}

		private static boolean isHidden(Path path) {
			try {
				return Files.isHidden(path);
			} catch (IOException e) {
				return false;
			}
		}
	}

	private record FileExtensionRule(Set<String> extensions, boolean only) implements ResourceFilter {

		@Override
		public boolean skipsFile(Path root, Path file, BasicFileAttributes attributes) {
			String name = file.getFileName().toString();
			int dot = name.lastIndexOf('.');
			String extension = dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
			return this.extensions.contains(extension) != this.only;
		}
	}

	/** Matches paths relative to its base, or to the walk's root if it has no base. */
	private record PathRule(Path base, List<PathPattern> patterns, boolean only) implements ResourceFilter {

		@Override
		public boolean skipsFolder(Path root, Path folder, BasicFileAttributes attributes) {
			return skips(root, folder, true);
		}

		@Override
		public boolean skipsFile(Path root, Path file, BasicFileAttributes attributes) {
			return skips(root, file, false);
		}

		private boolean skips(Path root, Path path, boolean folder) {
			Path relativeTo = this.base == null ? root : this.base;
			List<String> segments = PathPattern.segmentsBelow(relativeTo, path);
			if (segments == null) {
				// outside the base: never matched, but a folder the base lies in is entered
				return this.only && !(folder && relativeTo.startsWith(path));
			}
			if (segments.isEmpty()) {
				return false;
			}
			return this.only ? !keeps(segments, folder) : skips(segments, folder, relativeTo, root);
		}

		private boolean keeps(List<String> segments, boolean folder) {
			for (PathPattern pattern : this.patterns) {
				if (pattern.matchesItselfOrAFolderAbove(segments, folder)
						|| folder && pattern.mayMatchBelow(segments)) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Matches the path itself and the folders between the base and the root, since the walk
		 * asked about the folders below the root already.
		 */
		private boolean skips(List<String> segments, boolean folder, Path relativeTo, Path root) {
			int rootDepth = root.startsWith(relativeTo) ? root.getNameCount() - relativeTo.getNameCount() : 0;
			for (PathPattern pattern : this.patterns) {
				if (pattern.matches(segments, folder)) {
					return true;
				}
				for (int length = 1; length <= rootDepth && length < segments.size(); length++) {
					if (pattern.matches(segments.subList(0, length), true)) {
						return true;
					}
				}
			}
			return false;
		}
	}

	/**
	 * Skips the files of a blacklist given as a file or as lines, which is resolved once per run
	 * when the rule is bound to the run.
	 */
	private record BlacklistRule(Path listFile, List<String> lines, Path base, BlacklistedPaths listed)
			implements ResourceFilter {

		@Override
		public boolean skipsFile(Path root, Path file, BasicFileAttributes attributes) {
			if (this.listed == null) {
				throw new IllegalStateException("A filter skipping blacklisted paths is to be created for a run first.");
			}
			return this.listed.contains(file);
		}

		@Override
		public ResourceFilter createForRun(ResourceFilterContext context) {
			BlacklistedPaths forRun = this.listFile != null
					? context.blacklistedPaths(this.listFile, this.base)
					: BlacklistedPaths.of(this.lines, this.base, "given as lines");
			return new BlacklistRule(this.listFile, this.lines, this.base, forRun);
		}
	}

	/** Skips what git ignores, asked through the run's context once it is bound to a run. */
	private record GitIgnoreRule(GitIgnoredPaths ignored) implements ResourceFilter {

		static final GitIgnoreRule UNBOUND = new GitIgnoreRule(null);

		@Override
		public boolean skipsFolder(Path root, Path folder, BasicFileAttributes attributes) {
			return ".git".equals(folder.getFileName().toString()) || ignored().isIgnored(folder, true);
		}

		@Override
		public boolean skipsFile(Path root, Path file, BasicFileAttributes attributes) {
			return ignored().isIgnored(file, false);
		}

		@Override
		public ResourceFilter createForRun(ResourceFilterContext context) {
			return new GitIgnoreRule(context.gitIgnoredPaths());
		}

		@Override
		public GitIgnoredPaths ignored() {
			if (this.ignored == null) {
				throw new IllegalStateException("A filter skipping what git ignores is to be created for a run first.");
			}
			return this.ignored;
		}
	}

}
