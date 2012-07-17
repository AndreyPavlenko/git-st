package com.googlecode.gitst.fastimport;

import java.io.IOException;
import java.io.PrintStream;
import java.text.DateFormat;
import java.util.Collection;

import com.googlecode.gitst.Exec;
import com.googlecode.gitst.ExecutionException;
import com.googlecode.gitst.Git;
import com.googlecode.gitst.Logger;
import com.googlecode.gitst.Logger.ProgressBar;
import com.googlecode.gitst.Repo;

/**
 * @author Andrey Pavlenko
 */
public class FastImport {
    private final DateFormat _logDateFormat = DateFormat.getDateTimeInstance(
            DateFormat.SHORT, DateFormat.SHORT);
    private final Repo _repo;
    private final Collection<Commit> _commits;
    private final Logger _log;

    public FastImport(final Repo repo, final Collection<Commit> commits,
            final Logger logger) {
        _repo = repo;
        _commits = commits;
        _log = logger;
    }

    public Repo getRepo() {
        return _repo;
    }

    public Collection<Commit> getCommits() {
        return _commits;
    }

    public Logger getLogger() {
        return _log;
    }

    public void exec() throws IOException, InterruptedException,
            ExecutionException {
        final Repo repo = getRepo();
        final Git git = repo.getGit();
        long mark = git.getLatestMark();
        final Exec exec = git.fastImport().exec();
        final Process proc = exec.getProcess();

        try {
            final String branch = repo.getBranchName();
            final Collection<Commit> commits = getCommits();
            final ProgressBar b = _log.createProgressBar(
                    "Importing to git...    ", commits.size());

            try (final PrintStream s = new PrintStream(proc.getOutputStream())) {
                for (final Commit cmt : getCommits()) {
                    commit(cmt, ++mark, branch, s);
                    b.done(1);
                }
            }

            if (exec.waitFor() != 0) {
                throw new ExecutionException(
                        "git fast-import failed with exit code: "
                                + proc.exitValue(), proc.exitValue());
            }
        } finally {
            proc.destroy();
        }
    }

    private void commit(final Commit cmt, final long mark, final String branch,
            final PrintStream s) throws IOException {
        final CommitId id = cmt.getId();
        final String committer = getRepo().toCommitter(id.getUserId());

        _log.echo(_logDateFormat.format(id.getTime()) + " " + committer + ':');
        _log.echo(cmt.getComment());
        _log.echo("");

        for (final FileChange c : cmt.getChanges()) {
            _log.echo(c);
        }

        _log.echo("--------------------------------------------------------------------------------");

        cmt.setMark(":" + mark);
        cmt.setBranch(branch);
        cmt.setCommitter(committer);

        if (mark != 1) {
            cmt.setFromCommittiSh(':' + String.valueOf(mark - 1));
        }

        cmt.write(s);
    }
}
