package com.googlecode.gitst.fastexport;

import static com.googlecode.gitst.RepoProperties.META_PROP_LAST_PUSH_SHA;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.TreeMap;

import com.googlecode.gitst.Exec;
import com.googlecode.gitst.ExecutionException;
import com.googlecode.gitst.Git;
import com.googlecode.gitst.Logger;
import com.googlecode.gitst.Logger.ProgressBar;
import com.googlecode.gitst.Marks;
import com.googlecode.gitst.Repo;
import com.googlecode.gitst.StreamReader;

/**
 * @author Andrey Pavlenko
 */
public class FastExport {
    private final Repo _repo;
    private final Logger _log;

    public FastExport(final Repo repo) {
        _repo = repo;
        _log = repo.getLogger();
    }

    public Repo getRepo() {
        return _repo;
    }

    public Map<Integer, Commit> loadChanges() throws ExecutionException,
            InterruptedException, IOException, UnsupportedCommandException {
        final Repo repo = getRepo();
        final Git git = repo.getGit();
        final String branch = repo.getBranchName();
        final Exec exec = git.fastExport(branch).exec();
        final Process proc = exec.getProcess();

        try {
            final Map<Integer, Commit> commits = loadChanges(proc
                    .getInputStream());

            if (exec.waitFor() != 0) {
                throw new ExecutionException(
                        "git fast-export failed with exit code: "
                                + proc.exitValue(), proc.exitValue());
            }

            return filter(commits);
        } finally {
            proc.destroy();
        }
    }

    public Map<Integer, Commit> loadChanges(final InputStream in)
            throws ExecutionException, InterruptedException, IOException,
            UnsupportedCommandException {
        final Repo repo = getRepo();
        final ExportStreamReader r = new ExportStreamReader(repo,
                new StreamReader(in));
        final Map<Integer, Commit> commits = r.readCommits();
        return commits;
    }

    public void submit(final Map<Integer, Commit> commits) throws IOException,
            FastExportException {
        final Repo repo = getRepo();
        final Logger log = repo.getLogger();
        final ProgressBar b = log.createProgressBar("Exporting to StarTeam",
                commits.size());

        for (final Commit cmt : commits.values()) {
            final Integer fromMark = cmt.getFrom();

            if (fromMark != null) {
                final Commit from = commits.get(fromMark);

                if (from != null) {
                    if (from.exec(repo)) {
                        b.done(1);
                    }
                }
            }

            if (cmt.exec(repo)) {
                b.done(1);
            }
        }
    }

    private Map<Integer, Commit> filter(final Map<Integer, Commit> commits)
            throws InterruptedException, IOException, ExecutionException {
        final Repo repo = getRepo();
        final Git git = repo.getGit();
        final String sha = repo.getRepoProperties().getProperty(
                META_PROP_LAST_PUSH_SHA, null);

        if (sha == null) {
            return commits;
        } else {
            final Marks marks = git.loadMarks(repo.getBranchName());
            int mark = 0;

            for (final Map.Entry<Integer, String> e : marks.entrySet()) {
                if (e.getValue().equals(sha)) {
                    mark = e.getKey();
                    break;
                }
            }
            if (mark == 0) {
                return commits;
            } else {
                final Map<Integer, Commit> m = new TreeMap<>();

                for (final Map.Entry<Integer, Commit> e : commits.entrySet()) {
                    final Integer key = e.getKey();

                    if (key > mark) {
                        m.put(key, e.getValue());
                    } else if (_log.isDebugEnabled()) {
                        _log.debug("Ignoring: " + e.getValue());
                    }
                }

                return m;
            }
        }
    }
}
