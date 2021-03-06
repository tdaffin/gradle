/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.logging.sink;

import net.jcip.annotations.ThreadSafe;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.StandardOutputListener;
import org.gradle.api.logging.configuration.ConsoleOutput;
import org.gradle.internal.Factory;
import org.gradle.internal.event.ListenerBroadcast;
import org.gradle.internal.logging.config.LoggingRouter;
import org.gradle.internal.logging.console.AnsiConsole;
import org.gradle.internal.logging.console.BuildLogLevelFilterRenderer;
import org.gradle.internal.logging.console.BuildStatusRenderer;
import org.gradle.internal.logging.console.ColorMap;
import org.gradle.internal.logging.console.Console;
import org.gradle.internal.logging.console.ConsoleLayoutCalculator;
import org.gradle.internal.logging.console.DefaultColorMap;
import org.gradle.internal.logging.console.DefaultWorkInProgressFormatter;
import org.gradle.internal.logging.console.StyledTextOutputBackedRenderer;
import org.gradle.internal.logging.console.ThrottlingOutputEventListener;
import org.gradle.internal.logging.console.UserInputConsoleRenderer;
import org.gradle.internal.logging.console.UserInputStandardOutputRenderer;
import org.gradle.internal.logging.console.WorkInProgressRenderer;
import org.gradle.internal.logging.events.EndOutputEvent;
import org.gradle.internal.logging.events.FlushOutputEvent;
import org.gradle.internal.logging.events.LogLevelChangeEvent;
import org.gradle.internal.logging.events.OutputEvent;
import org.gradle.internal.logging.events.OutputEventListener;
import org.gradle.internal.logging.events.ProgressCompleteEvent;
import org.gradle.internal.logging.events.ProgressEvent;
import org.gradle.internal.logging.events.ProgressStartEvent;
import org.gradle.internal.logging.format.PrettyPrefixedLogHeaderFormatter;
import org.gradle.internal.logging.text.StreamBackedStandardOutputListener;
import org.gradle.internal.logging.text.StreamingStyledTextOutput;
import org.gradle.internal.nativeintegration.console.ConsoleMetaData;
import org.gradle.internal.nativeintegration.console.FallbackConsoleMetaData;
import org.gradle.internal.time.Clock;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link OutputEventListener} implementation which renders output events to various
 * destinations. This implementation is thread-safe.
 */
@ThreadSafe
public class OutputEventRenderer implements OutputEventListener, LoggingRouter {
    private final Object lock = new Object();
    private final AtomicReference<LogLevel> logLevel = new AtomicReference<LogLevel>(LogLevel.LIFECYCLE);
    private final Clock clock;
    private final ListenerBroadcast<OutputEventListener> formatters = new ListenerBroadcast<OutputEventListener>(OutputEventListener.class);
    private final ListenerBroadcast<StandardOutputListener> stdoutListeners = new ListenerBroadcast<StandardOutputListener>(StandardOutputListener.class);
    private final ListenerBroadcast<StandardOutputListener> stderrListeners = new ListenerBroadcast<StandardOutputListener>(StandardOutputListener.class);

    private ColorMap colourMap;
    private OutputStream originalStdOut;
    private OutputStream originalStdErr;
    private StreamBackedStandardOutputListener stdOutListener;
    private StreamBackedStandardOutputListener stdErrListener;
    private OutputEventListener console;

    public OutputEventRenderer(final Clock clock) {
        this.clock = clock;
        formatters.add(
            new LazyListener(new Factory<OutputEventListener>() {
                @Override
                public OutputEventListener create() {
                    OutputEventListener stdOutChain = new UserInputStandardOutputRenderer(new StyledTextOutputBackedRenderer(new StreamingStyledTextOutput(stdoutListeners.getSource())), clock);
                    OutputEventListener stdErrChain = new StyledTextOutputBackedRenderer(new StreamingStyledTextOutput(stderrListeners.getSource()));

                    return new BuildLogLevelFilterRenderer(
                        new ProgressLogEventGenerator(new LogEventDispatcher(stdOutChain, stdErrChain), false)
                    );
                }
            })
        );
    }

    @Override
    public Snapshot snapshot() {
        synchronized (lock) {
            // Currently only snapshot the console output listener. Should snapshot all output listeners, and cleanup in restore()
            return new SnapshotImpl(logLevel.get(), console);
        }
    }

    @Override
    public void restore(Snapshot state) {
        synchronized (lock) {
            SnapshotImpl snapshot = (SnapshotImpl) state;
            if (snapshot.logLevel != logLevel.get()) {
                configure(snapshot.logLevel);
            }

            // TODO - also close console when it is replaced
            if (snapshot.console != console) {
                if (snapshot.console == null) {
                    formatters.remove(console);
                    console.onOutput(new EndOutputEvent());
                    console = null;
                } else {
                    throw new UnsupportedOperationException("Cannot restore previous console. This is not implemented yet.");
                }
            }
        }
    }

    public ColorMap getColourMap() {
        synchronized (lock) {
            if (colourMap == null) {
                colourMap = new DefaultColorMap();
            }
        }
        return colourMap;
    }

    @Override
    public void flush() {
        onOutput(new FlushOutputEvent());
    }

    public OutputStream getOriginalStdOut() {
        return originalStdOut;
    }

    public OutputStream getOriginalStdErr() {
        return originalStdErr;
    }

    public void attachProcessConsole(ConsoleOutput consoleOutput) {
        synchronized (lock) {
            ConsoleConfigureAction.execute(this, consoleOutput);
        }
    }

    @Override
    public void attachConsole(OutputStream outputStream, ConsoleOutput consoleOutput) {
        synchronized (lock) {
            if (consoleOutput == ConsoleOutput.Plain) {
                addPlainConsole(new StreamBackedStandardOutputListener(outputStream));
            } else {
                ConsoleMetaData consoleMetaData = FallbackConsoleMetaData.INSTANCE;
                OutputStreamWriter writer = new OutputStreamWriter(outputStream);
                Console console = new AnsiConsole(writer, writer, getColourMap(), consoleMetaData, true);
                addRichConsole(console, true, true, consoleMetaData, consoleOutput == ConsoleOutput.Verbose);
            }
        }
    }

    public void attachSystemOutAndErr() {
        addStandardOutputListener();
        addStandardErrorListener();
    }

    private void addStandardOutputListener() {
        synchronized (lock) {
            originalStdOut = System.out;
            if (stdOutListener != null) {
                stdoutListeners.remove(stdOutListener);
            }
            stdOutListener = new StreamBackedStandardOutputListener((Appendable) System.out);
            addStandardOutputListener(stdOutListener);
        }
    }

    private void addStandardErrorListener() {
        synchronized (lock) {
            originalStdErr = System.err;
            if (stdErrListener != null) {
                stderrListeners.remove(stdErrListener);
            }
            stdErrListener = new StreamBackedStandardOutputListener((Appendable) System.err);
            addStandardErrorListener(stdErrListener);
        }
    }

    private void removeStandardOutputListener() {
        synchronized (lock) {
            flush();
            if (stdOutListener != null) {
                stdoutListeners.remove(stdOutListener);
                stdOutListener = null;
            }
        }
    }

    private void removeStandardErrorListener() {
        synchronized (lock) {
            flush();
            if (stdErrListener != null) {
                stderrListeners.remove(stdErrListener);
                stdErrListener = null;
            }
        }
    }

    public void addOutputEventListener(OutputEventListener listener) {
        synchronized (lock) {
            flush();
            formatters.add(listener);
        }
    }

    public void removeOutputEventListener(OutputEventListener listener) {
        synchronized (lock) {
            flush();
            formatters.remove(listener);
        }
    }

    public OutputEventRenderer addRichConsole(Console console, boolean stdout, boolean stderr, ConsoleMetaData consoleMetaData) {
        return addRichConsole(console, stdout, stderr, consoleMetaData, false);
    }

    public OutputEventRenderer addRichConsole(Console console, boolean stdout, boolean stderr, ConsoleMetaData consoleMetaData, boolean verbose) {
        final OutputEventListener consoleChain = new ThrottlingOutputEventListener(
            new UserInputConsoleRenderer(
                new BuildStatusRenderer(
                    new WorkInProgressRenderer(
                        new BuildLogLevelFilterRenderer(
                            new GroupingProgressLogEventGenerator(new StyledTextOutputBackedRenderer(console.getBuildOutputArea()), clock, new PrettyPrefixedLogHeaderFormatter(), verbose)),
                        console.getBuildProgressArea(), new DefaultWorkInProgressFormatter(consoleMetaData), new ConsoleLayoutCalculator(consoleMetaData)),
                    console.getStatusBar(), console, consoleMetaData, clock),
                console),
                clock);
        return addConsoleChain(consoleChain, stdout, stderr);
    }

    public OutputEventRenderer addPlainConsole() {
        return addPlainConsole(stdOutListener);
    }

    private OutputEventRenderer addPlainConsole(StandardOutputListener outputListener) {
        OutputEventListener stdoutChain = new UserInputStandardOutputRenderer(new StyledTextOutputBackedRenderer(new StreamingStyledTextOutput(outputListener)), clock);
        OutputEventListener consoleChain = new ThrottlingOutputEventListener(
            new BuildLogLevelFilterRenderer(
                new GroupingProgressLogEventGenerator(stdoutChain, clock, new PrettyPrefixedLogHeaderFormatter(), true)
            ),
            clock
        );

        return addConsoleChain(consoleChain, true, true);
    }

    private OutputEventRenderer addConsoleChain(OutputEventListener consoleChain, boolean stdout, boolean stderr) {
        synchronized (lock) {
            if (stdout && stderr) {
                this.console = consoleChain;
                removeStandardOutputListener();
                removeStandardErrorListener();
            } else if (stdout) {
                this.console = onNonError(consoleChain);
                removeStandardOutputListener();
            } else if (stderr) {
                this.console = onError(consoleChain);
                removeStandardErrorListener();
            } else {
                this.console = consoleChain;
            }
            consoleChain.onOutput(new LogLevelChangeEvent(logLevel.get()));
            formatters.add(this.console);
        }
        return this;
    }

    private OutputEventListener onError(final OutputEventListener listener) {
        return new LogEventDispatcher(null, listener);
    }

    private OutputEventListener onNonError(final OutputEventListener listener) {
        return new LogEventDispatcher(listener, null);
    }

    public void addStandardErrorListener(StandardOutputListener listener) {
        synchronized (lock) {
            flush();
            stderrListeners.add(listener);
        }
    }

    public void addStandardOutputListener(StandardOutputListener listener) {
        synchronized (lock) {
            flush();
            stdoutListeners.add(listener);
        }
    }

    public void addStandardOutputListener(OutputStream outputStream) {
        addStandardOutputListener(new StreamBackedStandardOutputListener(outputStream));
    }

    public void addStandardErrorListener(OutputStream outputStream) {
        addStandardErrorListener(new StreamBackedStandardOutputListener(outputStream));
    }


    public void removeStandardOutputListener(StandardOutputListener listener) {
        synchronized (lock) {
            flush();
            stdoutListeners.remove(listener);
        }
    }

    public void removeStandardErrorListener(StandardOutputListener listener) {
        synchronized (lock) {
            flush();
            stderrListeners.remove(listener);
        }
    }

    public void configure(LogLevel logLevel) {
        onOutput(new LogLevelChangeEvent(logLevel));
    }

    @Override
    public void onOutput(OutputEvent event) {
        if (event.getLogLevel() != null && event.getLogLevel().compareTo(logLevel.get()) < 0 && !isProgressEvent(event)) {
            return;
        }
        if (event instanceof LogLevelChangeEvent) {
            LogLevelChangeEvent changeEvent = (LogLevelChangeEvent) event;
            LogLevel newLogLevel = changeEvent.getNewLogLevel();
            if (newLogLevel == this.logLevel.get()) {
                return;
            }
            this.logLevel.set(newLogLevel);
        }
        synchronized (lock) {
            formatters.getSource().onOutput(event);
        }
    }

    private boolean isProgressEvent(OutputEvent event) {
        return event instanceof ProgressStartEvent || event instanceof ProgressEvent || event instanceof ProgressCompleteEvent;
    }

    private static class SnapshotImpl implements Snapshot {
        private final LogLevel logLevel;
        private final OutputEventListener console;

        SnapshotImpl(LogLevel logLevel, OutputEventListener console) {
            this.logLevel = logLevel;
            this.console = console;
        }
    }

    private static class LazyListener implements OutputEventListener {
        private Factory<OutputEventListener> factory;
        private OutputEventListener delegate;

        private LazyListener(Factory<OutputEventListener> factory) {
            this.factory = factory;
        }

        @Override
        public void onOutput(OutputEvent event) {
            if (delegate == null) {
                delegate = factory.create();
                factory = null;
            }
            delegate.onOutput(event);
        }
    }
}
