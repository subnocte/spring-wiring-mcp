package io.github.subnocte.springwiring.scanner;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared parsing front-end for the indexes: parses every file once with a current
 * language level and collects the failures instead of dropping them.
 *
 * @param units    successfully parsed files
 * @param failures files the parser could not process — reported, never silently skipped
 */
public record ParsedSources(List<ParsedSource> units, List<ParseFailure> failures) {

    /** A successfully parsed source file. */
    public record ParsedSource(CompilationUnit cu, Path path) {
    }

    /** Parses all files. Unparsable files land in {@link #failures()}; IO errors abort. */
    public static ParsedSources parse(List<Path> sourceFiles) {
        // The scanned codebase may use newer syntax than StaticJavaParser's default
        // level (JAVA_11) accepts; a level below the target's loses whole files.
        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        List<ParsedSource> units = new ArrayList<>();
        List<ParseFailure> failures = new ArrayList<>();
        for (Path file : sourceFiles) {
            try {
                units.add(new ParsedSource(StaticJavaParser.parse(file), file));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read source file: " + file, e);
            } catch (ParseProblemException e) {
                failures.add(new ParseFailure(file.toString(), firstProblem(e)));
            }
        }
        return new ParsedSources(List.copyOf(units), List.copyOf(failures));
    }

    private static String firstProblem(ParseProblemException e) {
        return e.getProblems().isEmpty()
                ? e.getMessage().lines().findFirst().orElse("unparsable")
                : e.getProblems().get(0).getVerboseMessage().lines().findFirst().orElse("unparsable");
    }
}
