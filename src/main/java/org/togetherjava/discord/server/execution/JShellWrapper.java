package org.togetherjava.discord.server.execution;

import jdk.jshell.Diag;
import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.togetherjava.discord.server.Config;
import org.togetherjava.discord.server.io.StringOutputStream;
import org.togetherjava.discord.server.sandbox.FilteredExecutionControlProvider;
import org.togetherjava.discord.server.sandbox.Sandbox;

import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JShellWrapper {

    private static final Logger LOGGER = LogManager.getLogger(JShellWrapper.class);

    private JShell jShell;
    private StringOutputStream outputStream;
    private Sandbox sandbox;
    private TimeWatchdog watchdog;

    public JShellWrapper(Config config, TimeWatchdog watchdog) {
        this.watchdog = watchdog;
        this.outputStream = new StringOutputStream(Character.BYTES * 1600);
        this.jShell = buildJShell(outputStream, config);
        this.sandbox = new Sandbox();
    }

    private JShell buildJShell(OutputStream outputStream, Config config) {
        try {
            PrintStream out = new PrintStream(outputStream, true, "UTF-8");
            return JShell.builder()
                    .out(out)
                    .err(out)
                    .executionEngine(getExecutionControlProvider(config), Map.of())
                    .build();
        } catch (UnsupportedEncodingException e) {
            LOGGER.warn("Unsupported encoding: UTF-8. How?", e);

            return JShell.create();
        }
    }

    private FilteredExecutionControlProvider getExecutionControlProvider(Config config) {
        return new FilteredExecutionControlProvider(
                config.getCommaSeparatedList("blocked.packages"),
                config.getCommaSeparatedList("blocked.classes"),
                config.getCommaSeparatedList("blocked.methods").stream()
                        .map(s -> {
                            String[] parts = s.split("#");
                            return new ImmutablePair<>(parts[0], parts[1]);
                        })
                        .collect(Collectors.toList())
        );
    }

    /**
     * Closes the {@link JShell} session.
     *
     * @see JShell#close()
     */
    public void close() {
        jShell.close();
    }

    /**
     * Evaluates a command and returns the resulting snippet events and stdout.
     * <p>
     * May throw an exception.
     *
     * @param command the command to run
     * @return the result of running it
     */
    public JShellResult eval(String command) {
        try {
            List<SnippetEvent> evaluate = watchdog.runWatched(() -> evaluate(command), jShell::stop);

            return new JShellResult(evaluate, getStandardOut());
        } finally {
            // always remove the output stream so it does not linger in case of an exception
            outputStream.reset();
        }
    }

    /**
     * Returns the diagnostics for the snippet. This includes things like compilation errors.
     *
     * @param snippet the snippet to return them for
     * @return all found diagnostics
     */
    public Stream<Diag> getSnippetDiagnostics(Snippet snippet) {
        return jShell.diagnostics(snippet);
    }

    private List<SnippetEvent> evaluate(String command) {
        return sandbox.runInSandBox(() -> jShell.eval(command));
    }

    private String getStandardOut() {
        return outputStream.toString();
    }

    /**
     * Wraps the result of executing JShell.
     */
    public static class JShellResult {
        private List<SnippetEvent> events;
        private String stdout;

        JShellResult(List<SnippetEvent> events, String stdout) {
            this.events = events;
            this.stdout = stdout == null ? "" : stdout;
        }

        public List<SnippetEvent> getEvents() {
            return Collections.unmodifiableList(events);
        }

        public String getStdOut() {
            return stdout;
        }
    }
}
