package com.googlecode.gitst;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Andrey Pavlenko
 */
public class Git {
    private static final String FILE_MARKS = ".marks";
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
        if (args != null) {
            final List<String> l = new ArrayList<>(args.length + 1);
            l.add(arg);

            for (final String a : args) {
                l.add(a);
            }

            return exec(l);
        } else {
            return exec(Collections.singletonList(arg));
        }
    }

    public Exec exec(final List<String> args) {
        final List<String> command = new ArrayList<>(args.size() + 1);
        command.add(getExecutable());
        command.addAll(args);
        return new Exec(getRepoDir(), command);
    }

    public Exec fastImport(final String... args) {
        final List<String> l = new ArrayList<>(args == null ? 3
                : 3 + args.length);
        final String marks = getMarksFile().getAbsolutePath();
        l.add("fast-import");
        l.add("--export-marks=" + marks);
        l.add("--import-marks-if-exists=" + marks);

        if (args != null) {
            for (final String a : args) {
                l.add(a);
            }
        }

        return exec(l);
    }

    public Exec fastExport(final String branch, final String... args) {
        final List<String> l = new ArrayList<>(args == null ? 6
                : 6 + args.length);
        final File f = getMarksFile();
        final String marks = f.getAbsolutePath();
        Exec exec;

        l.add("fast-export");
        l.add("--no-data");
        l.add("-C");
        l.add("--export-marks=" + marks);

        if (f.exists()) {
            l.add("--import-marks=" + marks);
        }
        if (args != null) {
            for (final String a : args) {
                l.add(a);
            }
        }

        l.add(branch);
        exec = exec(l);
        exec.setOutStream(null);
        return exec;
    }

    public boolean fileExists(final String branch, final String path)
            throws IOException {
        try {
            final Exec exec = exec("cat-file", "-t", branch + ':' + path);
            exec.setOutStream(null);
            exec.setErrStream(null);
            return exec.exec().waitFor() == 0;
        } catch (final InterruptedException ex) {
            return false;
        }
    }

    public InputStream catFile(final String sha) throws IOException {
        final Exec exec = exec("cat-file", "blob", sha);
        exec.setOutStream(null);
        return exec.exec().getProcess().getInputStream();
    }

    public File catFile(final String sha, final File toFile) throws IOException {
        final byte[] buff = new byte[8192];

        try (InputStream in = catFile(sha);
                OutputStream out = new FileOutputStream(toFile)) {
            for (int i = in.read(buff); i != -1; i = in.read(buff)) {
                out.write(buff, 0, i);
            }
        }

        return toFile;
    }

    public Exec checkout(final String... args) {
        return exec("checkout", args);
    }

    public File getMarksFile() {
        final File repoDir = getRepoDir();
        File gitDir = new File(getRepoDir(), ".git");

        if (!gitDir.isDirectory()) {
            // Bare repository
            gitDir = repoDir;
        }

        return new File(new File(gitDir, "git-st"), FILE_MARKS);
    }
}
