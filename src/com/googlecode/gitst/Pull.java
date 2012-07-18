package com.googlecode.gitst;

import static com.googlecode.gitst.RepoProperties.META_PROP_LAST_SYNC_DATE;
import static com.googlecode.gitst.RepoProperties.PROP_PASSWORD;
import static com.googlecode.gitst.RepoProperties.PROP_USER;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Collection;

import com.googlecode.gitst.fastimport.Commit;
import com.googlecode.gitst.fastimport.FastImport;
import com.starbase.starteam.View;
import com.starbase.util.OLEDate;

/**
 * @author Andrey Pavlenko
 */
public class Pull {
    private final Repo _repo;
    private final Logger _log;

    public Pull(final Repo repo, final Logger logger) {
        _repo = repo;
        _log = logger;
    }

    public static void main(final String[] args) {
        if (args.length == 0) {
            printHelp(System.out);
        } else {
            try {
                final Args a = new Args(args);
                final String dir = a.get("-d", ".");
                final String confDir = a.get("-c", null);
                final String user = a.get("-u", null);
                final String password = a.get("-p", null);
                final RepoProperties props = new RepoProperties(
                        new java.io.File(dir), confDir == null ? null
                                : new java.io.File(confDir));

                if (user != null) {
                    props.setSessionProperty(PROP_USER, user);
                }
                if (password != null) {
                    props.setSessionProperty(PROP_PASSWORD, password);
                }

                try (final Repo repo = new Repo(props)) {
                    final File tempDir = new File(props.getGitstDir(), "tmp");
                    tempDir.mkdirs();
                    System.setProperty("java.io.tmpdir", tempDir.getPath());
                    new Pull(repo, Logger.createConsoleLogger()).pull();
                }
            } catch (final IllegalArgumentException ex) {
                System.err.println(ex.getMessage());
                printHelp(System.err);
                System.exit(1);
            } catch (final IOException | ConfigurationException
                    | InterruptedException ex) {
                System.err.println(ex.getMessage());
                System.exit(1);
            } catch (final ExecutionException ex) {
                System.exit(ex.getExitCode());
            }
        }
    }

    public Repo getRepo() {
        return _repo;
    }

    public Logger getLogger() {
        return _log;
    }

    public void pull() throws IOException, InterruptedException,
            ExecutionException {
        long time = System.currentTimeMillis();
        final Repo repo = getRepo();
        final View view = repo.connect();
        final Logger logger = getLogger();
        final RepoProperties props = repo.getRepoProperties();
        final FastImport fastImport = new FastImport(repo, logger);
        final String lastSync = props.getMetaProperty(META_PROP_LAST_SYNC_DATE);
        final OLEDate startDate = (lastSync == null) ? view.getCreatedTime()
                : new OLEDate(Double.parseDouble(lastSync));
        final OLEDate endDate = repo.getServer().getCurrentTime();
        final Collection<Commit> commits = fastImport.loadChanges(startDate,
                endDate, true);

        if (!commits.isEmpty()) {
            _log.echo();
            fastImport.exec(commits);
            props.setMetaProperty(META_PROP_LAST_SYNC_DATE,
                    String.valueOf(endDate.getDoubleValue()));
            props.saveMeta();

            if (!repo.isBare()) {
                repo.getGit().checkout(repo.getBranchName()).exec().waitFor();
            }
        } else {
            _log.echo("No changes found");
            _log.echo();
        }

        time = (System.currentTimeMillis() - time) / 1000;
        _log.echo("Total time: "
                + ((time / 3600) + "h:" + ((time % 3600) / 60) + "m:"
                        + (time % 60) + "s"));
    }

    private static void printHelp(final PrintStream ps) {
        ps.println("Usage: git st pull [-u <user>] [-p password] [-d <directory>] [-c <confdir>]");
    }
}
