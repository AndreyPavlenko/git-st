package com.googlecode.gitst;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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
        final List<String> l = new ArrayList<>(args == null ? 5
                : 5 + args.length);
        final File f = getMarksFile();
        final String marks = f.getAbsolutePath();
        Exec exec;

        l.add("fast-export");
        l.add("--no-data");
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

    public long getLatestMark() throws IOException {
        final File marks = getMarksFile();

        if (marks.isFile()) {
            try (InputStream in = new BufferedInputStream(new FileInputStream(
                    marks))) {
                final StreamReader r = new StreamReader(in);
                long max = 0L;

                for (String s = r.readLine(); s != null; s = r.readLine()) {
                    final long mark = Long.parseLong(s.substring(
                            s.indexOf(':') + 1, s.indexOf(' ')));
                    if (mark > max) {
                        max = mark;
                    }
                }

                return max;
            }
        }

        return 0L;
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

    private File getMarksFile() {
        final File repoDir = getRepoDir();
        File gitDir = new File(getRepoDir(), ".git");

        if (!gitDir.isDirectory()) {
            // Bare repository
            gitDir = repoDir;
        }

        return new File(new File(gitDir, "git-st"), FILE_MARKS);
    }
}
