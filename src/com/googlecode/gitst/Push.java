package com.googlecode.gitst;

import static com.googlecode.gitst.RepoProperties.PROP_PASSWORD;
import static com.googlecode.gitst.RepoProperties.PROP_USER;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;

import com.googlecode.gitst.fastexport.Commit;
import com.googlecode.gitst.fastexport.FastExport;
import com.googlecode.gitst.fastexport.FastExportException;

/**
 * @author Andrey Pavlenko
 */
public class Push {

    private final Repo _repo;
    private final Logger _log;

    public Push(final Repo repo) {
        _repo = repo;
        _log = repo.getLogger();
    }

    public static void main(final String[] args) throws FastExportException {
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

                try (final Repo repo = new Repo(props,
                        Logger.createConsoleLogger())) {
                    new Push(repo).push();
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

    public void push() throws IOException, InterruptedException,
            ExecutionException, FastExportException {
        long time = System.currentTimeMillis();
        final Repo repo = getRepo();
        final FastExport exp = new FastExport(repo);
        final File marksFile = repo.getGit().getMarksFile();
        final Marks marks = repo.getMarks();
        boolean ok = false;

        try {
            final Map<Integer, Commit> commits = exp.loadChanges();

            if (commits.isEmpty()) {
                repo.getLogger().echo("No changes found");
            } else {
                exp.submit(commits);
                throw new RuntimeException();
            }

            time = (System.currentTimeMillis() - time) / 1000;
            _log.echo("Total time: "
                    + ((time / 3600) + "h:" + ((time % 3600) / 60) + "m:"
                            + (time % 60) + "s"));
            ok = true;
        } finally {
            if (!ok && !marks.isEmpty()) {
                marks.store(marksFile);
            }
        }
    }

    private static void printHelp(final PrintStream ps) {
        ps.println("Usage: git st push [-u <user>] [-p password] [-d <directory>] [-c <confdir>]");
    }
}
