package com.googlecode.gitst;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;

import com.googlecode.gitst.Logger.Level;
import com.googlecode.gitst.fastexport.FastExportException;

/**
 * @author Andrey Pavlenko
 */
public class Remote {
    private final Logger _log;

    public Remote(final Logger logger) {
        _log = logger;
    }

    public Logger getLogger() {
        return _log;
    }

    public static void main(final String[] args) {
        if (args.length == 0) {
            System.err.println("Remote repository name is not specified");
            System.exit(1);
        }
        final String remote = args[0];
        final Logger log = new Logger(System.err,
                !"false".equalsIgnoreCase(System.getenv("GITST_PB")));
        final Remote r = new Remote(log);

        try {
            r.exec(remote, System.in, System.out);
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

    public void exec(final String remoteName, final InputStream in,
            final OutputStream out) throws IOException, InterruptedException,
            ExecutionException, FastExportException {
        final Git git = new Git();
        final RepoProperties props = new RepoProperties(git, remoteName);
        final PrintWriter w = new PrintWriter(out, true);
        final StreamReader r = new StreamReader(in);
        boolean dryRun = false;
        String line = r.readLine();

        if ("capabilities".equals(line)) {
            w.print("option\n");
            w.print("push\n");
            w.print("import\n\n");
            w.flush();

            read: for (line = r.readLine(); line != null; line = r.readLine()) {
                _log.debug(line);
                switch (line = line.trim()) {
                case "":
                    continue;
                case "list":
                case "list for-push":
                    w.print("? " + props.getBranchName() + "\n\n");
                    w.flush();
                    break;
                case "option":
                default:
                    if (line.startsWith("option")) {
                        switch (line) {
                        case "option progress true":
                            _log.setProgressBarSupported(true);
                            w.print("ok\n");
                            w.flush();
                            continue read;
                        case "option progress false":
                            _log.setProgressBarSupported(false);
                            w.print("ok\n");
                            w.flush();
                            continue read;
                        case "option dry-run true":
                            dryRun = true;
                            w.print("ok\n");
                            w.flush();
                            continue read;
                        case "option dry-run false":
                            dryRun = false;
                            w.print("ok\n");
                            w.flush();
                            continue read;
                        case "option verbosity 0":
                            _log.setLevel(Level.ERROR);
                            w.print("ok\n");
                            w.flush();
                            continue read;
                        case "option verbosity 1":
                            _log.setLevel(Level.INFO);
                            w.print("ok\n");
                            w.flush();
                            continue read;
                        case "option verbosity 2":
                            _log.setLevel(Level.DEBUG);
                            w.print("ok\n");
                            w.flush();
                            continue read;
                        default:
                            w.print("unsupported\n");
                            w.flush();
                            continue read;
                        }
                    } else if (line.startsWith("import")) {
                        pull(props, out, dryRun);
                        w.print("done\n");
                        w.flush();
                    } else if (line.startsWith("push")) {
                        push(props, dryRun);
                        w.print("ok "
                                + line.substring(line.lastIndexOf(':') + 1)
                                + "\n\n");
                        w.flush();
                    } else {
                        throw new UnsupportedOperationException(
                                "Unsupported command: " + line);
                    }
                }
            }
        } else {
            throw new UnsupportedOperationException("Unexpected command: "
                    + line);
        }
    }

    private void pull(final RepoProperties props, final OutputStream out,
            final boolean dryRun) throws IOException, InterruptedException,
            ExecutionException {

        if (props.getMetaProperty(RepoProperties.META_PROP_LAST_PULL_DATE) == null) {
            Init.setDefaults(props, props.getGit(), "master");
        }

        final Repo repo = new Repo(props, getLogger());
        final Pull pull = new Pull(repo);
        pull.pull(out, dryRun);
    }

    private void push(final RepoProperties props, final boolean dryRun)
            throws IOException, InterruptedException, ExecutionException,
            FastExportException {
        final Repo repo = new Repo(props, getLogger());
        final Push push = new Push(repo);
        push.push(dryRun);
    }
}
