package com.googlecode.gitst.fastexport;

import java.io.IOException;
import java.util.Map;

import com.googlecode.gitst.Exec;
import com.googlecode.gitst.ExecutionException;
import com.googlecode.gitst.Git;
import com.googlecode.gitst.Logger;
import com.googlecode.gitst.Logger.ProgressBar;
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
        _log.echo("Executing git fast-export");

        final Repo repo = getRepo();
        final Git git = repo.getGit();
        final String branch = repo.getBranchName();
        final Exec exec = git.fastExport(branch).exec();
        final Process proc = exec.getProcess();

        try {
            final ExportStreamReader r = new ExportStreamReader(repo,
                    new StreamReader(proc.getInputStream()));
            final Map<Integer, Commit> commits = r.readCommits();

            if (exec.waitFor() != 0) {
                throw new ExecutionException(
                        "git fast-export failed with exit code: "
                                + proc.exitValue(), proc.exitValue());
            }

            return commits;
        } finally {
            proc.destroy();
        }
    }

    public void submit(final Map<Integer, Commit> commits) throws IOException,
            FastExportException {
        final Repo repo = getRepo();
        final Logger log = repo.getLogger();
        log.echo("Exporting to StarTeam");
        final ProgressBar b = log.createProgressBar(
                "Exporting to StarTeam...    ", commits.size());

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
}
