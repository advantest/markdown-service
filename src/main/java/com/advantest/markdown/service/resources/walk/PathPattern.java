/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.resources.walk;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A GLOB pattern matched against a path relative to a base folder, one path segment after the
 * other, the same way on every operating system.
 * 
 * <p>The syntax follows the one of <code>.gitignore</code> files:</p>
 * <ul>
 * <li>A pattern without a <code>/</code>, e.g. <code>*.class</code>, matches a name at any
 * depth.</li>
 * <li>A pattern with a <code>/</code> at its start or inside, e.g. <code>/target</code> or
 * <code>doc/images</code>, is anchored at the base folder.</li>
 * <li>A pattern ending with <code>/</code>, e.g. <code>target/</code>, matches folders only.</li>
 * <li>A segment <code>**</code> matches any number of folders. At the end of a pattern it matches
 * everything below, but not the folder itself, so <code>doc/**</code> matches what is inside
 * <code>doc</code>.</li>
 * <li>Inside a segment, <code>*</code> matches any number of characters, <code>?</code> one
 * character, <code>[abc]</code> and <code>[a-z]</code> one of the characters named,
 * <code>[!abc]</code> one character not named, and <code>{a,b}</code> one of the alternatives,
 * which may hold <code>*</code>, <code>?</code> and <code>[...]</code> themselves.
 * <code>\</code> takes the next character as it is.</li>
 * </ul>
 * 
 * <p>Names are compared case-sensitively and the separator is always <code>/</code>, so a pattern
 * means the same on every operating system.</p>
 */
final class PathPattern {

	private static final String ANY_FOLDERS = "**";

	private static final String CHARACTERS_ESCAPED_IN_REGEX = "\\.[]{}()<>*+-=!?^$|&";

	private final String glob;

	/** <code>null</code> stands for {@value #ANY_FOLDERS}. */
	private final List<Segment> segments;

	private final boolean foldersOnly;

	private PathPattern(String glob, List<Segment> segments, boolean foldersOnly) {
		this.glob = glob;
		this.segments = segments;
		this.foldersOnly = foldersOnly;
	}

	/**
	 * Compiles the given GLOB pattern.
	 * 
	 * @param glob the pattern, must neither be <code>null</code> nor empty
	 * @return the compiled pattern, never <code>null</code>
	 * @throws IllegalArgumentException if the pattern is <code>null</code>, empty, has an empty
	 *                                  segment, e.g. <code>a//b</code>, ends with <code>\</code>,
	 *                                  has a bracket or brace that is not closed, or braces inside
	 *                                  braces
	 */
	static PathPattern compile(String glob) {
		if (glob == null || glob.isEmpty()) {
			throw new IllegalArgumentException("A path pattern must neither be null nor empty.");
		}

		String rest = glob;
		boolean foldersOnly = rest.endsWith("/");
		if (foldersOnly) {
			rest = rest.substring(0, rest.length() - 1);
		}
		boolean anchored = rest.startsWith("/");
		if (anchored) {
			rest = rest.substring(1);
		}
		if (rest.isEmpty()) {
			throw new IllegalArgumentException("A path pattern must name something: " + glob);
		}
		anchored |= rest.contains("/");

		List<Segment> segments = new ArrayList<>();
		if (!anchored) {
			segments.add(null);
		}
		for (String segment : rest.split("/", -1)) {
			if (segment.isEmpty()) {
				throw new IllegalArgumentException("A path pattern must not have an empty segment: " + glob);
			}
			if (ANY_FOLDERS.equals(segment)) {
				if (segments.isEmpty() || segments.getLast() != null) {
					segments.add(null);
				}
			} else {
				segments.add(Segment.compile(segment, glob));
			}
		}

		return new PathPattern(glob, Collections.unmodifiableList(segments), foldersOnly);
	}

	/**
	 * Splits the given path into its segments relative to the given base folder.
	 * 
	 * @param base the base folder, absolute and normalized
	 * @param path the path, absolute and normalized
	 * @return the names from below the base down to the path, empty if the path is the base, or
	 *         <code>null</code> if the path does not lie in the base
	 */
	static List<String> segmentsBelow(Path base, Path path) {
		if (!path.startsWith(base)) {
			return null;
		}
		List<String> names = new ArrayList<>(path.getNameCount() - base.getNameCount());
		for (Path name : base.relativize(path)) {
			String text = name.toString();
			if (!text.isEmpty()) {
				names.add(text);
			}
		}
		return names;
	}

	/**
	 * Tells whether the pattern matches the given path or a folder above it, down from the base.
	 * 
	 * @param segments the path's segments relative to the base
	 * @param folder whether the path is a folder
	 * @return <code>true</code> if the pattern matches the path or one of the folders it lies in
	 */
	boolean matchesItselfOrAFolderAbove(List<String> segments, boolean folder) {
		int count = segments.size();
		for (int length = 1; length <= count; length++) {
			if (matches(segments.subList(0, length), length < count || folder)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Tells whether the pattern matches the given path itself.
	 * 
	 * @param segments the path's segments relative to the base
	 * @param folder whether the path is a folder
	 * @return <code>true</code> if the pattern matches
	 */
	boolean matches(List<String> segments, boolean folder) {
		return (folder || !this.foldersOnly) && matchesFrom(0, segments, 0);
	}

	/**
	 * Tells whether something below the given folder could be matched, so that a walk looking for
	 * matches has to enter the folder.
	 * 
	 * @param folderSegments the folder's segments relative to the base
	 * @return <code>true</code> if a path below the folder could match
	 */
	boolean mayMatchBelow(List<String> folderSegments) {
		return mayMatchBelowFrom(0, folderSegments, 0);
	}

	private boolean matchesFrom(int patternIndex, List<String> names, int nameIndex) {
		if (patternIndex == this.segments.size()) {
			return nameIndex == names.size();
		}
		Segment segment = this.segments.get(patternIndex);
		if (segment == null) {
			if (patternIndex == this.segments.size() - 1) {
				// a trailing "**" matches what is inside, not the folder itself
				return nameIndex < names.size();
			}
			for (int next = nameIndex; next <= names.size(); next++) {
				if (matchesFrom(patternIndex + 1, names, next)) {
					return true;
				}
			}
			return false;
		}
		return nameIndex < names.size()
				&& segment.matches(names.get(nameIndex))
				&& matchesFrom(patternIndex + 1, names, nameIndex + 1);
	}

	private boolean mayMatchBelowFrom(int patternIndex, List<String> names, int nameIndex) {
		if (nameIndex == names.size()) {
			return patternIndex < this.segments.size();
		}
		if (patternIndex == this.segments.size()) {
			return false;
		}
		Segment segment = this.segments.get(patternIndex);
		if (segment == null) {
			return mayMatchBelowFrom(patternIndex + 1, names, nameIndex)
					|| mayMatchBelowFrom(patternIndex, names, nameIndex + 1);
		}
		return segment.matches(names.get(nameIndex)) && mayMatchBelowFrom(patternIndex + 1, names, nameIndex + 1);
	}

	@Override
	public String toString() {
		return this.glob;
	}

	/** One segment of a pattern, compared as a string if it holds no wildcard. */
	private record Segment(String literal, Pattern regex) {

		boolean matches(String name) {
			return this.literal != null ? this.literal.equals(name) : this.regex.matcher(name).matches();
		}

		static Segment compile(String segment, String glob) {
			StringBuilder regex = new StringBuilder();
			StringBuilder literal = new StringBuilder();
			boolean wildcard = false;
			boolean inBraces = false;

			for (int index = 0; index < segment.length(); index++) {
				char character = segment.charAt(index);
				switch (character) {
					case '\\' -> {
						if (index + 1 == segment.length()) {
							throw new IllegalArgumentException("A path pattern must not end a segment with \\: " + glob);
						}
						index++;
						appendLiteral(segment.charAt(index), regex, literal);
					}
					case '*' -> {
						regex.append(".*");
						wildcard = true;
					}
					case '?' -> {
						regex.append('.');
						wildcard = true;
					}
					case '[' -> {
						index = appendCharacterClass(segment, index, regex, glob);
						wildcard = true;
					}
					case '{' -> {
						if (inBraces) {
							throw new IllegalArgumentException("A path pattern must not nest braces: " + glob);
						}
						inBraces = true;
						regex.append("(?:");
						wildcard = true;
					}
					case ',' -> {
						if (inBraces) {
							regex.append('|');
						} else {
							appendLiteral(character, regex, literal);
						}
					}
					case '}' -> {
						if (inBraces) {
							inBraces = false;
							regex.append(')');
						} else {
							appendLiteral(character, regex, literal);
						}
					}
					default -> appendLiteral(character, regex, literal);
				}
			}
			if (inBraces) {
				throw new IllegalArgumentException("A path pattern must close its braces: " + glob);
			}

			return wildcard ? new Segment(null, Pattern.compile(regex.toString()))
					: new Segment(literal.toString(), null);
		}

		private static void appendLiteral(char character, StringBuilder regex, StringBuilder literal) {
			literal.append(character);
			if (CHARACTERS_ESCAPED_IN_REGEX.indexOf(character) >= 0) {
				regex.append('\\');
			}
			regex.append(character);
		}

		/** Appends the class starting at the given index and answers the index of its closing bracket. */
		private static int appendCharacterClass(String segment, int start, StringBuilder regex, String glob) {
			int index = start + 1;
			regex.append('[');
			if (index < segment.length() && segment.charAt(index) == '!') {
				regex.append('^');
				index++;
			}
			int first = index;
			for (; index < segment.length(); index++) {
				char character = segment.charAt(index);
				if (character == ']' && index > first) {
					regex.append(']');
					return index;
				}
				boolean range = character == '-' && index > first && index + 1 < segment.length()
						&& segment.charAt(index + 1) != ']';
				if (!range && "\\[]^&-".indexOf(character) >= 0) {
					regex.append('\\');
				}
				regex.append(character);
			}
			throw new IllegalArgumentException("A path pattern must close its brackets: " + glob);
		}
	}

}
