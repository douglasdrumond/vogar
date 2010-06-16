/*
 * Copyright (C) 2009 The Android Open Source Project
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

package vogar;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import vogar.commands.Command;
import vogar.commands.CommandFailedException;
import vogar.commands.Mkdir;

/**
 * Compiles, installs, runs and reports on actions.
 */
final class Driver {

    /**
     * Assign each runner thread a unique ID. Necessary so threads don't
     * conflict when selecting a monitor port.
     */
    private final ThreadLocal<Integer> runnerThreadId = new ThreadLocal<Integer>() {
        private int next = 0;
        @Override protected synchronized Integer initialValue() {
            return next++;
        }
    };

    private Timer actionTimeoutTimer = new Timer("action timeout", true);

    private final File localTemp;
    private final ExpectationStore expectationStore;
    private final Mode mode;
    private final XmlReportPrinter reportPrinter;
    private final int firstMonitorPort;
    private final int monitorTimeoutSeconds;
    private final int smallTimeoutSeconds;
    private final int largeTimeoutSeconds;
    private final ClassFileIndex classFileIndex;
    private final int numRunnerThreads;

    private int successes = 0;
    private int failures = 0;
    private List<String> failureNames = new ArrayList<String>();
    private int skipped = 0;
    private List<String> skippedNames = new ArrayList<String>();

    private final Map<String, Action> actions = Collections.synchronizedMap(
            new LinkedHashMap<String, Action>());
    private final Map<String, Outcome> outcomes = Collections.synchronizedMap(
            new LinkedHashMap<String, Outcome>());

    private Set<File> allSuggestedJars = new HashSet<File>();

    public Driver(File localTemp, Mode mode, ExpectationStore expectationStore,
                  XmlReportPrinter reportPrinter, int monitorTimeoutSeconds, int firstMonitorPort,
                  int smallTimeoutSeconds, int largeTimeoutSeconds, ClassFileIndex classFileIndex,
                  int numRunnerThreads) {
        this.localTemp = localTemp;
        this.expectationStore = expectationStore;
        this.mode = mode;
        this.reportPrinter = reportPrinter;
        this.monitorTimeoutSeconds = monitorTimeoutSeconds;
        this.firstMonitorPort = firstMonitorPort;
        this.smallTimeoutSeconds = smallTimeoutSeconds;
        this.largeTimeoutSeconds = largeTimeoutSeconds;
        this.classFileIndex = classFileIndex;
        this.numRunnerThreads = numRunnerThreads;
    }

    /**
     * Builds and executes the actions in the given files.
     */
    public void buildAndRun(Collection<File> files, Collection<String> classes) {
        if (!actions.isEmpty()) {
            throw new IllegalStateException("Drivers are not reusable");
        }

        new Mkdir().mkdirs(localTemp);

        filesToActions(files);
        classesToActions(classes);

        if (actions.isEmpty()) {
            Console.getInstance().info("Nothing to do.");
            return;
        }

        Console.getInstance().info("Actions: " + actions.size());
        final long t0 = System.currentTimeMillis();

        // mode.prepare before mode.buildAndInstall to ensure the runner is
        // built. packaging of activity APK files needs the runner along with
        // the action-specific files.
        mode.prepare();

        // build and install actions in a background thread. Using lots of
        // threads helps for packages that contain many unsupported actions
        final BlockingQueue<Action> readyToRun = new ArrayBlockingQueue<Action>(4);

        ExecutorService builders = Threads.threadPerCpuExecutor();

        int totalToRun = 0;
        for (final Action action : actions.values()) {
            final String name = action.getName();
            if (outcomes.containsKey(name)) {
                addEarlyResult(outcomes.get(name));
                continue;
            } else if (expectationStore.get(name).getResult() == Result.UNSUPPORTED) {
                addEarlyResult(new Outcome(name, Result.UNSUPPORTED,
                        "Unsupported according to expectations file"));
                continue;
            }

            final int runIndex = totalToRun++;
            builders.submit(new Runnable() {
                public void run() {
                    try {
                        Console.getInstance().verbose("installing action " + runIndex + "; "
                                + readyToRun.size() + " are runnable");
                        Outcome outcome = mode.buildAndInstall(action);
                        if (outcome != null) {
                            outcomes.put(name, outcome);
                        }

                        readyToRun.put(action);
                        Console.getInstance().verbose("installed action " + runIndex + "; "
                                + readyToRun.size() + " are runnable");
                    } catch (Throwable e) {
                        Console.getInstance().info("unexpected failure!", e);
                    }
                }
            });
        }
        builders.shutdown();

        Console.getInstance().verbose(numRunnerThreads > 1
                ? ("running actions in parallel (" + numRunnerThreads + " threads)")
                : ("running actions in serial"));

        ExecutorService runners = Threads.fixedThreadsExecutor(numRunnerThreads);

        final AtomicBoolean prematurelyExhaustedInput = new AtomicBoolean();
        for (int i = 0; i < totalToRun; i++) {
            runners.submit(new ActionRunner(prematurelyExhaustedInput, i, readyToRun));
        }
        runners.shutdown();
        try {
            runners.awaitTermination(60 * 60 * 24 * 28, TimeUnit.SECONDS); // four weeks
        } catch (InterruptedException e) {
            recordOutcome(new Outcome("vogar.Vogar", Result.ERROR, e));
        }

        if (prematurelyExhaustedInput.get()) {
            recordOutcome(new Outcome("vogar.Vogar", Result.ERROR,
                    "Expected " + actions.size() + " actions but found fewer."));
        }

        if (reportPrinter != null) {
            Console.getInstance().info("Printing XML Reports... ");
            int numFiles = reportPrinter.generateReports(outcomes.values());
            Console.getInstance().info(numFiles + " XML files written.");
        }

        mode.shutdown();
        final long t1 = System.currentTimeMillis();

        if (failures > 0) {
            Collections.sort(failureNames);
            Console.getInstance().summarizeFailures(failureNames);
        }

        if (skipped > 0) {
            Collections.sort(skippedNames);
            Console.getInstance().summarizeSkips(skippedNames);
        }

        List<String> jarStringList = new ArrayList<String>();
        for (File jar : allSuggestedJars) {
            jarStringList.add(jar.getPath());
        }
        if (!jarStringList.isEmpty()) {
            Console.getInstance().warn("consider adding the following to the classpath:",
                    jarStringList);
        }

        if (failures > 0 || skipped > 0) {
            Console.getInstance().info(String.format(
                    "Outcomes: %s. Passed: %d, Failed: %d, Skipped: %d. Took %s.",
                    (successes + failures + skipped), successes, failures, skipped,
                    TimeUtilities.msToString(t1 - t0)));
        } else {
            Console.getInstance().info(String.format("Outcomes: %s. All successful. Took %s.",
                    successes, TimeUtilities.msToString(t1 - t0)));
        }
    }

    private void classesToActions(Collection<String> classNames) {
        for (String className : classNames) {
            Action action = new Action(className, className, null, null, null);
            actions.put(action.getName(), action);
        }
    }

    private void filesToActions(Collection<File> files) {
        for (File file : files) {
            new ActionFinder(actions, outcomes).findActions(file);
        }
    }

    private synchronized void addEarlyResult(Outcome earlyFailure) {
        if (earlyFailure.getResult() == Result.UNSUPPORTED) {
            Console.getInstance().verbose("skipped " + earlyFailure.getName());
            skipped++;

        } else {
            for (String line : earlyFailure.getOutputLines()) {
                Console.getInstance().streamOutput(earlyFailure.getName(), line + "\n");
            }
            recordOutcome(earlyFailure);
        }
    }

    private synchronized void recordOutcome(Outcome outcome) {
        outcomes.put(outcome.getName(), outcome);
        Expectation expectation = expectationStore.get(outcome);
        ResultValue resultValue;
        if (outcome.matters()) {
            resultValue = expectation.matches(outcome) ? ResultValue.OK : ResultValue.FAIL;
        } else {
            resultValue = ResultValue.IGNORE;
        }

        if (resultValue == ResultValue.OK) {
            successes++;
        } else if (resultValue == ResultValue.FAIL) {
            failures++;
            failureNames.add(outcome.getName());
        } else { // ResultValue.IGNORE
            skipped++;
            skippedNames.add(outcome.getName());
        }

        Result result = outcome.getResult();
        Console.getInstance().outcome(outcome.getName());
        Console.getInstance().printResult(outcome.getName(), result, resultValue);

        suggestJars(outcome);
    }

    private void suggestJars(Outcome outcome) {
        Result result = outcome.getResult();
        if (result != Result.COMPILE_FAILED && result != Result.EXEC_FAILED) {
            return;
        }
        Set<File> suggestedJars = classFileIndex.suggestClasspaths(outcome.getOutputLines());

        // don't suggest adding a jar that's already on the classpath
        suggestedJars.removeAll(mode.getClasspath().getElements());

        if (!suggestedJars.isEmpty()) {
            allSuggestedJars.addAll(suggestedJars);
            List<String> jarStringList = new ArrayList<String>();
            for (File jar : suggestedJars) {
                jarStringList.add(jar.getPath());
            }
            Console.getInstance().warn(
                    "may have failed because some of these jars are missing from the classpath:",
                    jarStringList);
        }
    }

    /**
     * Runs a single action and reports the result.
     */
    private class ActionRunner implements Runnable, HostMonitor.Handler {

        /**
         * All action runners share this atomic boolean. Whenever any of them
         * waits for five minutes without retrieving an executable action, we
         * use this to signal that they should all quit.
         */
        private final AtomicBoolean prematurelyExhaustedInput;
        private final int count;
        private final BlockingQueue<Action> readyToRun;
        private volatile Date killTime;

        public ActionRunner(AtomicBoolean prematurelyExhaustedInput, int count, BlockingQueue<Action> readyToRun) {
            this.prematurelyExhaustedInput = prematurelyExhaustedInput;
            this.count = count;
            this.readyToRun = readyToRun;
        }

        public int monitorPort(int defaultValue) {
            return numRunnerThreads == 1
                    ? defaultValue
                    : firstMonitorPort + (runnerThreadId.get() % numRunnerThreads);
        }

        public void run() {
            if (prematurelyExhaustedInput.get()) {
                return;
            }

            Console.getInstance().verbose("executing action " + count + "; "
                    + readyToRun.size() + " are ready to run");

            // if it takes 5 minutes for build and install, something is broken
            Action action;
            try {
                action = readyToRun.poll(5 * 60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                recordOutcome(new Outcome("vogar.Vogar", Result.ERROR, e));
                return;
            }

            if (action == null) {
                prematurelyExhaustedInput.set(true); // short-circuit subsequent runners
                return;
            }

            execute(action);
            mode.cleanup(action);
        }

        /**
         * Executes a single action and then prints the result.
         */
        private void execute(final Action action) {
            Console.getInstance().action(action.getName());
            Expectation expectation = expectationStore.get(action.getName());
            int timeoutSeconds = expectation.getTags().contains("large")
                    ? largeTimeoutSeconds
                    : smallTimeoutSeconds;

            Outcome earlyFailure = outcomes.get(action.getName());
            if (earlyFailure != null) {
                addEarlyResult(earlyFailure);
                return;
            }

            final Command command = mode.createActionCommand(action, monitorPort(-1));
            Future<List<String>> consoleOut = command.executeLater();
            final AtomicReference<Result> result = new AtomicReference<Result>();

            if (timeoutSeconds != 0) {
                resetKillTime(timeoutSeconds);
                scheduleTaskKiller(command, result, timeoutSeconds);
            }

            HostMonitor hostMonitor = new HostMonitor(monitorTimeoutSeconds);
            boolean completedNormally = hostMonitor.monitor(monitorPort(firstMonitorPort), this);
            if (completedNormally) {
                if (result.compareAndSet(null, Result.SUCCESS)) {
                    command.destroy();
                }
                return; // outcomes will have been reported via outcome()
            }

            if (result.compareAndSet(null, Result.ERROR)) {
                Console.getInstance().verbose("killing " + action + " because it could not be monitored.");
                command.destroy();
            }

            try {
                addEarlyResult(new Outcome(action.getName(), action.getName(), result.get(), consoleOut.get()));
            } catch (Exception e) {
                if (e.getCause() instanceof CommandFailedException) {
                    addEarlyResult(new Outcome(action.getName(), action.getName(), result.get(),
                            ((CommandFailedException) e.getCause()).getOutputLines()));
                } else if (result.get() == Result.EXEC_TIMEOUT) {
                    addEarlyResult(new Outcome(action.getName(), result.get(),
                            "killed because it timed out after " + timeoutSeconds + " seconds"));
                } else {
                    addEarlyResult(new Outcome(action.getName(), result.get(), e));
                }
            }
        }

        private void scheduleTaskKiller(
                final Command command, final AtomicReference<Result> result, final int timeoutSeconds) {
            actionTimeoutTimer.schedule(new TimerTask() {
                @Override public void run() {
                    // if the kill time has been pushed back, reschedule
                    if (System.currentTimeMillis() < killTime.getTime()) {
                        scheduleTaskKiller(command, result, timeoutSeconds);
                        return;
                    }
                    if (result.compareAndSet(null, Result.EXEC_TIMEOUT)) {
                        Console.getInstance().verbose("killing command that timed out after "
                                + timeoutSeconds + " seconds: " + command);
                        command.destroy();
                    }
                }
            }, killTime);
        }

        /**
         * Sets the time at which we'll kill a task that starts right now.
         */
        private void resetKillTime(int timeoutForTest) {
            /*
             * Give the target process an extra 2 seconds to self-timeout and report
             * the error. This way, when a JUnit test has one slow method, we don't
             * end up killing the whole process.
             */
            long delay = TimeUnit.SECONDS.toMillis(timeoutForTest + 2);
            killTime = new Date(System.currentTimeMillis() + delay);
        }

        public void output(String outcomeName, String output) {
            Console.getInstance().outcome(outcomeName);
            Console.getInstance().streamOutput(outcomeName, output);
        }

        public void outcome(Outcome outcome) {
            resetKillTime(smallTimeoutSeconds); // TODO: support flexible timeouts for JUnit tests
            recordOutcome(outcome);
        }
    }
}
