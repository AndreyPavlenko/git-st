package com.googlecode.gitst;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Andrey Pavlenko
 */
public class Git {
    private final File _repoDir;
    private final String _executable = "git";

    public Git(final File repoDir) {
        _repoDir = repoDir;
    }

    public File getRepoDir() {
        return _repoDir;
    }

    public String getExecutable() {
        return _executable;
    }

    public Exec exec(final String arg, final String... args) {
        final List<String> command = new ArrayList<>();
        command.add(getExecutable());
        command.add(arg);

        if (args != null) {
            for (final String a : args) {
                command.add(a);
            }
        }

        return new Exec(getRepoDir(), command);
    }

    public Exec fastImport(final String... args) {
        return exec("fast-import", args);
    }

    public Exec checkout(final String... args) {
        return exec("checkout", args);
    }

    public String getCurrentSha(final java.io.File dir, final String branch)
            throws InterruptedException, IOException {
        final Exec exec = new Exec(dir, "git", "log", "-1",
                "--pretty=format:%H", branch);
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
        exec.setOutStream(baos);
        exec.exec().waitFor();
        return new String(baos.toByteArray());
    }
}
