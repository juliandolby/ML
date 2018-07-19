/******************************************************************************

 * Copyright (c) 2018 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *****************************************************************************/
package com.ibm.wala.cast.python.ml.driver;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticRelatedInformation;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.python.icu.util.UResourceBundleIterator;

import com.ibm.wala.cast.lsp.WALAServer;
import com.ibm.wala.cast.lsp.Util;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.cast.python.ml.driver.PythonDriver;

public class PythonLinter {
	public static String positionToString(Position pos) {
		return "" + (pos.getLine()+1) + ":" + (pos.getCharacter()+1);
	}
	
	public static String rangeToString(Range range) {
		return positionToString(range.getStart()) + "-" + positionToString(range.getEnd());
		
	}
	
	public static String locationToString(String uri, Range range) {
		return uri + ":" + rangeToString(range);
	}

	public static String locationToString(Location loc) {
		return locationToString(loc.getUri(), loc.getRange());
	}
	
	public static void displayTextRange(String pre, PrintStream out, String uri, String[] lines, Range range) {
		final Position start = range.getStart();
		final Position end = range.getEnd();
		if(start.getLine() > end.getLine()) {
			throw new IllegalArgumentException("Invalid range: end line " + end.getLine() + " before start line " + start.getLine() + " of " + uri);
		}
		if(end.getLine() >= lines.length) {
			throw new IllegalArgumentException("Invalid range: end line " + end.getLine() + " after the last line " + lines.length + " of " + uri);			
		}
		for(int i = start.getLine(); i <= end.getLine(); i++) {
			out.print(pre);
			String prefix = uri + ":" + (i+1) + ":    ";
			out.print(prefix);
			String line = lines[i];
			out.println(line);
			int skipStart = 0;
			if(i == start.getLine()) {
				skipStart = start.getCharacter();
			}
			int numArrows = line.length();
			if(i == end.getLine()) {
				numArrows = end.getCharacter();
			}
			numArrows = numArrows - skipStart;
			
			out.print(pre);
			out.print(Stream.generate(()->" ").limit(prefix.length() + skipStart).collect(Collectors.joining()));
			if(numArrows >= 0) {
				out.println(Stream.generate(()->"^").limit(numArrows).collect(Collectors.joining()));
			}
		}
	}
	
	public static void displayDiagnostic(String pre, PrintStream out, String uri, Diagnostic diagnostic, Map<String,String[]> lines) {
		out.print(pre);
		out.print(locationToString(uri, diagnostic.getRange()));
		out.print(":    [");
		out.print(diagnostic.getSeverity().toString().toLowerCase());
		out.print("] ");
		out.println(diagnostic.getMessage());
		if(lines.containsKey(uri)) {
			displayTextRange(pre, out, uri, lines.get(uri), diagnostic.getRange());

		}
		
		List<DiagnosticRelatedInformation> relatedInfos = diagnostic.getRelatedInformation();
		String relatedPre = "    " + pre;
		for(DiagnosticRelatedInformation related : relatedInfos) {
			final Location loc = related.getLocation();
			if(loc == null) {
				 continue;
			}
			
			out.print(relatedPre);
			out.print(locationToString(loc));
			out.print(":    [related] ");
			out.println(related.getMessage());
			final String relatedUri = loc.getUri();
			if(lines.containsKey(relatedUri)) {
				displayTextRange(relatedPre, out, uri, lines.get(relatedUri), loc.getRange());
			}
		}
	}		
	
		
	public static Map<String, List<Diagnostic>> getDiagnostics(String language, Map<String,String> uriTextPairs) {
		return WALAServer.getDiagnostics(PythonDriver.python, language, uriTextPairs);
	}

	public static Map<String, List<Diagnostic>> getDiagnostics(Map<String,String> uriTextPairs) {
		return getDiagnostics("python", uriTextPairs);
	}

	static enum FORMAT {
		json{
			@Override
			public void print(PrintStream out, Map<String, String> texts, Map<String, List<Diagnostic>> diagnostics) {
				if(diagnostics == null || diagnostics.isEmpty()) {
					out.println("{}");
					return;
				}
				
				out.println("{");
				boolean pastFirst = false;
				for(Entry<String, List<Diagnostic>> entry : diagnostics.entrySet()) {
					if(pastFirst) {
						out.println();
						out.print(", ");
					} else {
						pastFirst = true;
					}
					out.print("\"");
					out.print(entry.getKey());
					out.print("\": [");
					List<Diagnostic> diags = entry.getValue();
					if(diags != null) {
						boolean pastFirstArr = false;
						for(Diagnostic diag : diags) {
							if(pastFirstArr) {
								out.println();
								out.print(", ");
							} else {
								pastFirstArr = true;
							}
							out.print(diags.toString());
						}
					}
					out.println("]");
				}
				out.println("}");
			}
		},
		pretty{
			@Override
			public void print(PrintStream out, Map<String, String> texts, Map<String, List<Diagnostic>> diagnostics) {
				final Map<String, String[]> lines = new HashMap<String, String[]>(texts.size());
				for(Map.Entry<String, String> kv : texts.entrySet()) {
					lines.put(kv.getKey(), 
	            	new BufferedReader(new StringReader(kv.getValue()))
	            		.lines()
	            		.toArray(String[]::new));
				}
				
				final String pre = "";
				for(Map.Entry<String, List<Diagnostic>> kv : diagnostics.entrySet()) {
					String uri = kv.getKey();
					if(kv.getValue() != null) {
						for(Diagnostic diagnostic : kv.getValue()) {
							displayDiagnostic(pre, out, uri, diagnostic, lines);
						}
					}
					
				}				
			}

		};
	
		public abstract void print(PrintStream out, Map<String, String> texts, Map<String, List<Diagnostic>> diagnostics);
	};
	private final static FORMAT default_format = FORMAT.pretty;

	static private String getFormatList() {
		return Arrays.stream(FORMAT.values())
		.map(x -> x.toString())
		.collect(Collectors.joining(","));
	}

	public static void main(String args[]) throws ClassHierarchyException, IOException, IllegalArgumentException, CancelException {
		final CommandLineParser optionParser = new DefaultParser();
		/* Command line options */
		final Options options = new Options();

		final Option formatOption = Option.builder().longOpt("format")
		.hasArg().argName("format")
		.desc("Format of output (" + getFormatList() + ").  Default: " + default_format.toString())
		.required(false).build();
		options.addOption(formatOption);

		final Option helpOpt = Option.builder().longOpt("help").argName("help")
			.desc("Print usage information").required(false).build();
	    options.addOption(helpOpt);
		
		FORMAT format = default_format;

		try {
			/* Parse command line */
			final CommandLine cmd = optionParser.parse(options, args);
			if (cmd.hasOption("help")) {
			  printUsage(options);
			  return;
			}


			final String formatString = cmd.getOptionValue("format");
			if(formatString != null) {
				try {
					format = FORMAT.valueOf(formatString);
				} catch(IllegalArgumentException e) {
					System.err.println("Error: format passed to --format option is not valid.  Please specify one of (" + getFormatList() + ")");

					printUsage(options);
					System.exit(1);
				}
			}

			List<String> files = cmd.getArgList();
			Map<String,String> uriTextPairs = new HashMap<String,String>();
			for(String fileName : files) {
				try {
					final Path path = Paths.get(fileName);
					String uri = Util.mangleUri(path.toUri().toString());
					String text = new String(Files.readAllBytes(path));
					if(uriTextPairs.containsKey(uri)) {
						System.err.println("WARNING: ignoring repeated filename: " + fileName);
					} else {
						uriTextPairs.put(fileName, text);
					}
				} catch(IOException e) {
					System.err.println("Failed to read file: " + fileName);
					System.exit(1);
				}
			}
			if(! uriTextPairs.isEmpty()) {
				Map<String, List<Diagnostic>> diagnostics = getDiagnostics(uriTextPairs);
				if(diagnostics == null) {
					System.err.println("There was an error generating diagnostics");
					System.exit(1);
				}
				format.print(System.out, uriTextPairs, diagnostics);
			}
		} catch (final ParseException e) {
			printUsage(options);
			System.err.println(e.getMessage());
			System.exit(-1);
		}
	}

	private static final String APP_NAME = "wala-lsp-linter-python-ml";
	private static final String APP_DESCRIPTION = "A Linter for WALA Python/ML analysis";

	private static void printUsage(final Options options) {
		final HelpFormatter formatter = new HelpFormatter();
		formatter.printHelp(APP_NAME, APP_DESCRIPTION, options, null);
	}

}
