/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service;

/**
 * Something that remembers answers and can be told to forget them.
 * 
 * <p>A check asking an address whether it is there, and a validator asking a service what is known
 * about a page, both keep what they were told, so that the same question costs the network once
 * rather than once per document. Nothing of that expires by itself, which is right for a program
 * running for a while and wrong for one running for a day: what an address answered in the morning
 * may be untrue in the afternoon.</p>
 * 
 * <p>Whoever holds such a memory says so by implementing this interface, and a
 * {@link MarkdownService} built with it passes {@link MarkdownService#clearCaches()} on to it. A
 * caller therefore tells the service it has, and needs to know neither which of its parts remember
 * anything nor what they are made of.</p>
 * 
 * <p>Forgetting is not closing: something told to forget answers again afterwards, asking anew.
 * Whoever wants a resource released says that elsewhere.</p>
 */
public interface CachesHolder {

	/**
	 * Forgets every remembered answer, so that the next question is asked again.
	 * 
	 * <p>This may be said while other threads are asking questions, and it is said for its effect
	 * on what comes afterwards: an answer already on its way is not taken back.</p>
	 */
	void clearCaches();

}
