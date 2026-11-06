/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Tells which version of this library runs.
 */
public final class MarkdownServiceInfo {
	
	/**
	 * The resource the build writes the version of this library into. It is named after the
	 * coordinates of this library, so that no other library on the class path can hide it.
	 */
	static final String VERSION_RESOURCE = "META-INF/com.advantest.markdown/markdown-service/version.properties";
	
	private static final String VERSION_KEY = "version";
	
	private static final String VERSION = readVersion(VERSION_RESOURCE);
	
	private MarkdownServiceInfo() {
	}
	
	/**
	 * @return the version of this library, or {@code null} if it cannot be read
	 */
	public static String getVersion() {
		return VERSION;
	}
	
	/**
	 * Reads the version a resource of this library names.
	 * 
	 * @return the version, or {@code null} if the resource cannot be read, names no version or was
	 *         not filled in by the build
	 */
	static String readVersion(String resource) {
		try (InputStream stream = MarkdownServiceInfo.class.getClassLoader().getResourceAsStream(resource)) {
			if (stream == null) {
				return null;
			}
			Properties properties = new Properties();
			properties.load(stream);
			String version = properties.getProperty(VERSION_KEY);
			if (version == null || version.isBlank() || version.contains("${")) {
				return null;
			}
			return version.strip();
		} catch (IOException | IllegalArgumentException e) {
			return null;
		}
	}
	
}