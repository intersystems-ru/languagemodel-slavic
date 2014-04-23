/*-
 * $Id$
 */
package com.intersystems.iknow.languagemodel.slavic.impl;

import static java.lang.System.getProperty;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toSet;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;

import com.atlascopco.hunspell.Hunspell;
import com.intersystems.iknow.languagemodel.slavic.MorphologicalAnalysisResult;
import com.intersystems.iknow.languagemodel.slavic.MorphologicalAnalyzer;

/**
 * Morphological analyzer which uses the <a href =
 * "http://hunspell.sourceforge.net">Hunspell</a> engine.
 *
 * @author Andrey Shcheglov (mailto:andrey.shcheglov@intersystems.com)
 */
public final class HunspellAnalyzer implements MorphologicalAnalyzer {
	private static final String POSIX_SEARCH_PATHS[] = {
		"/usr/share/hunspell",
		"/usr/local/share/hunspell",
		getProperty("user.dir"),
	};

	private static final String DARWIN_SEARCH_PATHS[] = {
		"/System/Library/Spelling",
		"/Library/Spelling",
		getProperty("user.home") + File.separatorChar + "Spelling",
		"/opt/local/share/hunspell",
		"/sw/share/hunspell",
	};

	private static final String DICTIONARY_SUFFIX = ".dic";

	private static final String AFFIX_SUFFIX = ".aff";

	/**
	 * Single-character tokens which may be returned by the analyzer,
	 * but shouldn't be treated as words. Hyphen (-) and apostrophe (')
	 * are not included, as they may be legal word parts ("красно-белый",
	 * "п'ятниця").
	 */
	private static final String WORD_DELIMITERS = "!\"()*,./:;<>?[]^`{} \t\r\n";

	private static final Pattern WORD_DELIMITERS_PATTERN;

	static {
		final StringBuilder regex = new StringBuilder();
		regex.append('[');
		WORD_DELIMITERS.chars().forEach(c -> {
			regex.append('\\').append((char) c);
		});
		regex.append("]+");
		WORD_DELIMITERS_PATTERN = Pattern.compile(regex.toString());
	}

	private final LinkedHashMap<String, Set<Hunspell>> analyzers = new LinkedHashMap<>();

	/**
	 * @throws IOException
	 */
	public HunspellAnalyzer() throws IOException {
		final Set<String> basenames = new LinkedHashSet<>(asList(
				"ru_RU",
				"uk_UA"));

		final Set<String> searchPaths = stream(POSIX_SEARCH_PATHS).map(File::new).filter(File::exists).map(file -> {
			try {
				return file.getCanonicalPath();
			} catch (final IOException ioe) {
				throw new UncheckedIOException(ioe);
			}
		}).collect(toSet());

		if (getProperty("os.name").equals("Mac OS X")) {
			stream(DARWIN_SEARCH_PATHS).map(File::new).filter(File::exists).map(file -> {
				try {
					return file.getCanonicalPath();
				} catch (final IOException ioe) {
					throw new UncheckedIOException(ioe);
				}
			}).forEach(canonicalPath -> {
				searchPaths.add(canonicalPath);
			});
		}


		final Iterator<String> it = searchPaths.iterator();
		while (it.hasNext()) {
			if (!new File(it.next()).exists()) {
				it.remove();
			}
		}

		final LinkedHashMap<String, Set<String>> dictionaries = new LinkedHashMap<>();

		basenames.stream().forEach(basename -> {
			searchPaths.stream().map(searchPath -> new File(searchPath, basename + DICTIONARY_SUFFIX)).forEach(dictionary -> {
				try {
					final File affix = new File(dictionary.getCanonicalFile().getParent(), basename + AFFIX_SUFFIX);
					if (dictionary.exists() && affix.exists()) {
						final String language = basename.substring(0, 2);
						Set<String> dictionaryGroup = dictionaries.get(language);
						if (dictionaryGroup == null) {
							dictionaryGroup = new LinkedHashSet<>();
							dictionaries.put(language, dictionaryGroup);
						}
						final String dictionaryPath = dictionary.getCanonicalPath();
						dictionaryGroup.add(dictionaryPath.substring(0, dictionaryPath.length() - 4));
					}
				} catch (final IOException ioe) {
					throw new UncheckedIOException(ioe);
				}
			});
		});

		dictionaries.entrySet().stream().forEach(dictionaryGroup -> {
			final Set<Hunspell> engineGroup = new LinkedHashSet<>();
			dictionaryGroup.getValue().stream().forEach(dictionary -> {
				engineGroup.add(new Hunspell(dictionary + DICTIONARY_SUFFIX, dictionary + AFFIX_SUFFIX));
			});
			this.analyzers.put(dictionaryGroup.getKey(), engineGroup);
		});
	}

	/**
	 * @see MorphologicalAnalyzer#analyze(String)
	 */
	@Override
	public Map<String, Set<MorphologicalAnalysisResult>> analyze(final String text) {
		if (text == null || text.length() == 0) {
			return emptyMap();
		}

		final Map<String, Set<MorphologicalAnalysisResult>> results = new LinkedHashMap<>();

		for (final Entry<String, Set<Hunspell>> analyzerGroup : this.analyzers.entrySet()) {
			analyzerGroup.getValue().stream().forEach(analyzer -> {
				split(text).stream().forEach(token -> {
					Set<MorphologicalAnalysisResult> resultsGroup = results.get(token);

					final List<String> readings = analyzer.stem(token);
					if (readings.isEmpty()) {
						if (resultsGroup == null) {
							/*
							 * Make sure tokens not present in the dictionary
							 * still appear in the results returned.
							 */
							results.put(token, Collections.<MorphologicalAnalysisResult>emptySet());
						}
					} else {
						for (final String reading : readings) {
							final MorphologicalAnalysisResult result = new MorphologicalAnalysisResult(analyzerGroup.getKey(), reading);
							if (resultsGroup == null || resultsGroup.isEmpty()) {
								resultsGroup = new LinkedHashSet<>();
								results.put(token, resultsGroup);
							}
							resultsGroup.add(result);
						}
					}
				});
			});
		}
		return results;
	}

	/**
	 * Splits <em>text</em> into individual words.
	 *
	 * @param text
	 */
	private static Set<String> split(final String text) {
		final Set<String> words = new LinkedHashSet<>();
		words.addAll(asList(WORD_DELIMITERS_PATTERN.split(text)));
		return words;
	}
}