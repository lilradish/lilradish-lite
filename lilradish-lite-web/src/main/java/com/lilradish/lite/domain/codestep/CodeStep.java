package com.lilradish.lite.domain.codestep;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.filling.FillPath;
import com.lilradish.lite.domain.wire.JsonValue;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The declaration is written in the same type as the code, so a change to either is seen by whoever reads the one file. */
public interface CodeStep {

    CodeStepName name();

    CodeStepDeclaration declaration();

    /** Open at most one file at a time; close it before another. To compare two, code may hold one in memory. */
    @FunctionalInterface
    interface FileInput {
        InputStream open();
    }

    /** Ordinary values and trusted content handles at their declared field positions. */
    record Input(JsonValue.JsonObject values, Map<FillPath, FileInput> files) {
        public Input {
            requireNonNull(values, "CodeStep.Input values must not be null");
            files = Map.copyOf(requireNonNull(files, "CodeStep.Input files must not be null"));
        }
    }

    /** A file to give back once, with its original name. */
    record FileOutput(String name, InputStream content) {
        public FileOutput {
            requireNonNull(name, "CodeStep.FileOutput name must not be null");
            requireNonNull(content, "CodeStep.FileOutput content must not be null");
        }
    }

    /** Ordinary returned values and file streams kept apart until the framework has verified the files. */
    record Output(JsonValue.JsonObject values, Map<FillPath, List<FileOutput>> files) {
        public Output {
            requireNonNull(values, "CodeStep.Output values must not be null");
            requireNonNull(files, "CodeStep.Output files must not be null");
            files = Map.copyOf(files.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey, entry -> List.copyOf(entry.getValue()))));
        }

        public static Output plain(JsonValue.JsonObject values) {
            return new Output(values, Map.of());
        }
    }

    @Nullable
    Output run(Input takes);
}
