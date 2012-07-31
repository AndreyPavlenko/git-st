package com.googlecode.gitst;

import static com.googlecode.gitst.RepoProperties.META_PROP_ITEM_FILTER;
import static com.googlecode.gitst.RepoProperties.META_PROP_LAST_PULL_DATE;
import static com.googlecode.gitst.RepoProperties.PROP_PASSWORD;
import static com.googlecode.gitst.RepoProperties.PROP_USER;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

import com.googlecode.gitst.Logger.Level;
import com.googlecode.gitst.fastimport.Commit;
import com.googlecode.gitst.fastimport.CommitId;
import com.googlecode.gitst.fastimport.FastImport;
import com.starbase.util.OLEDate;

/**
 * @author Andrey Pavlenko
 */
public class Pull {
    private final Repo _repo;
    private final Logger _log;

    public Pull(final Repo repo) {
        _repo = repo;
        _log = repo.getLogger();
    }

    public static void main(final String[] args) {
        final Args a = new Args(args);
        final String user = a.get("-u", null);
        final String password = a.get("-p", null);
        final File dir = new File(a.get("-d", "."));
        final boolean dryRun = a.hasOption("--dry-run");
        final Level level = a.hasOption("-v") ? Level.DEBUG : a.hasOption("-q")
                ? Level.ERROR : Level.INFO;
        final Logger log = Logger.createConsoleLogger(level);

        try {
            final Git git = new Git(dir);
            final RepoProperties props = new RepoProperties(git, "origin");

            if (user != null) {
                props.setSessionProperty(PROP_USER, user);
            }
            if (password != null) {
                props.setSessionProperty(PROP_PASSWORD, password);
            }

            try (final Repo repo = new Repo(props, log)) {
                new Pull(repo).pull(dryRun);
            }
        } catch (final IllegalArgumentException ex) {
            if (!log.isDebugEnabled()) {
                log.error(ex.getMessage());
            } else {
                log.error(ex.getMessage(), ex);
            }

            printHelp(log);
            System.exit(1);
        } catch (final ExecutionException ex) {
            if (!log.isDebugEnabled()) {
                log.error(ex.getMessage());
            } else {
                log.error(ex.getMessage(), ex);
            }

            System.exit(ex.getExitCode());
        } catch (final Throwable ex) {
            if (!log.isDebugEnabled()) {
                log.error(ex.getMessage());
            } else {
                log.error(ex.getMessage(), ex);
            }

            System.exit(1);
        }
    }

    public Repo getRepo() {
        return _repo;
    }

    public void pull() throws IOException, InterruptedException,
            ExecutionException {
        pull(null, false);
    }

    public void pull(final boolean dryRun) throws IOException,
            InterruptedException, ExecutionException {
        pull(null, dryRun);
    }

    public void pull(final OutputStream out, final boolean dryRun)
            throws IOException, InterruptedException, ExecutionException {
        long time = System.currentTimeMillis();
        final Repo repo = getRepo();
        final RepoProperties props = repo.getRepoProperties();
        final FastImport fastImport = new FastImport(repo);
        final OLEDate endDate = repo.getServer().getCurrentTime();
        final String lastPull = props.getMetaProperty(META_PROP_LAST_PULL_DATE);
        final Map<CommitId, Commit> commits;

        // Initial import
        if (lastPull == null) {
            commits = fastImport.loadChanges(endDate);
        } else {
            final OLEDate startDate = new OLEDate(Double.parseDouble(lastPull));
            commits = fastImport.loadChanges(startDate, endDate);
        }

        if (commits.isEmpty()) {
            _log.info("No changes found");
            _log.info("");
        } else if (dryRun) {
            dryRun(commits);
        } else {
            if (out == null) {
                fastImport.submit(commits.values());
            } else {
                fastImport.submit(commits.values(), out);
            }
        }

        if (_log.isInfoEnabled()) {
            time = (System.currentTimeMillis() - time) / 1000;
            _log.info("Total time: "
                    + ((time / 3600) + "h:" + ((time % 3600) / 60) + "m:"
                            + (time % 60) + "s"));
        }

        if (!dryRun && (out == null) && !commits.isEmpty() && !repo.isBare()) {
            _log.info("Executing git reset --merge");
            repo.getGit().exec("reset", "--merge").exec().waitFor();
        }

        if (!dryRun) {
            props.setMetaProperty(META_PROP_LAST_PULL_DATE,
                    String.valueOf(endDate.getDoubleValue()));
            props.setMetaProperty(META_PROP_ITEM_FILTER, null);
            props.saveMeta();
        }
    }

    private void dryRun(final Map<CommitId, Commit> commits) {
        final Repo repo = getRepo();

        for (final Commit cmt : commits.values()) {
            final CommitId id = cmt.getId();
            final String committer = repo.toCommitter(id.getUserId());
            cmt.setCommitter(committer);

            if (_log.isInfoEnabled()) {
                _log.info(cmt);
                _log.info("--------------------------------------------------------------------------------");
            }
        }

        _log.info("");
    }

    private static void printHelp(final Logger log) {
        log.error("Usage: git st pull [-u <user>] [-p password] [-d <directory>] "
                + "[--dry-run] [-v] [-q]");
    }
}
