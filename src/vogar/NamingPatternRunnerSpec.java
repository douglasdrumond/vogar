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
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A code finder that traverses through the directory tree looking for matching
 * naming patterns.
 */
abstract class NamingPatternRunnerSpec implements RunnerSpec {

    private final String PACKAGE_PATTERN = "(?m)^\\s*package\\s+(\\S+)\\s*;";

    private final String TYPE_DECLARATION_PATTERN
            = "(?m)\\b(?:public|private)\\s+(?:final\\s+)?(?:interface|class|enum)\\b";

    public Set<Action> findActions(File searchDirectory) {
        Set<Action> result = new LinkedHashSet<Action>();
        findActionsRecursive(result, searchDirectory);
        return result;
    }

    /**
     * Returns true if {@code file} contains a action class of this type.
     */
    protected boolean matches(File file) {
        return (!file.getName().startsWith(".")
                && file.getName().endsWith(".java"));
    }

    private void findActionsRecursive(Set<Action> sink, File file) {
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                findActionsRecursive(sink, child);
            }
            return;
        }

        if (!matches(file)) {
            return;
        }

        String className = fileToClass(file);
        File sourcePath = fileAndClassToSourcePath(file, className);
        sink.add(new Action(className, className, null, sourcePath, file, this));
    }

    /**
     * Returns the Java classname for the given file. For example, given the
     * input {@code luni/src/test/java/org/apache/harmony/luni/tests/java/util/ArrayListTest.java},
     * this returns {@code org.apache.harmony.luni.tests.java.util.ArrayListTest}.
     */
    private String fileToClass(File file) {
        String filePath = file.getPath();
        if (!filePath.endsWith(".java")) {
            throw new IllegalArgumentException("Not a .java file: " + file);
        }

        // We can get the unqualified class name from the path.
        // It's the last element minus the trailing ".java".
        String filename = file.getName();
        String className = filename.substring(0, filename.length() - 5);

        // For the package, the only foolproof way is to look for the package
        // declaration inside the file.
        try {
            String content = Strings.readFile(file);
            Pattern packagePattern = Pattern.compile(PACKAGE_PATTERN);
            Matcher packageMatcher = packagePattern.matcher(content);
            if (!packageMatcher.find()) {
                // if it doesn't have a package, make sure there's at least a
                // type declaration otherwise we're probably reading the wrong
                // kind of file.
                if (Pattern.compile(TYPE_DECLARATION_PATTERN).matcher(content).find()) {
                    return className;
                }
                throw new IllegalArgumentException("Not a .java file: '" + file + "'\n" + content);
            }
            String packageName = packageMatcher.group(1);
            return packageName + "." + className;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Couldn't read '" + file + "': " + ex.getMessage());
        }
    }

    /**
     * Returns the Java source path for given a class name found in the given file. For example, given the
     * inputs {@code luni/src/test/java/org/apache/harmony/luni/tests/java/util/ArrayListTest.java} 
     * and {@code org.apache.harmony.luni.tests.java.util.ArrayListTest}, this returns
     * {@code luni/src}.
     */
    private File fileAndClassToSourcePath(File file, String className) {
        String longPath = file.getPath();
        String shortPath = className.replace('.', File.separatorChar) + ".java";

        if (!longPath.endsWith(shortPath)) {
            throw new IllegalArgumentException("Class " + className
                                               + " expected to be in file ending in " + shortPath
                                               + " but instead found in " + longPath);
        }
        String sourcePath = longPath.substring(0, longPath.length() - shortPath.length());
        return new File(sourcePath);
    }
}