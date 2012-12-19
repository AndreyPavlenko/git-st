package com.aap.gitst;

import static com.aap.gitst.RepoProperties.PROP_CA;
import static com.aap.gitst.RepoProperties.PROP_CATHREADS;
import static com.aap.gitst.RepoProperties.PROP_DEFAULT_CATHREADS;
import static com.aap.gitst.RepoProperties.PROP_DEFAULT_IGNORE;
import static com.aap.gitst.RepoProperties.PROP_DEFAULT_MAXCONNECTIONS;
import static com.aap.gitst.RepoProperties.PROP_DEFAULT_USER_PATTERN;
import static com.aap.gitst.RepoProperties.PROP_FETCH;
import static com.aap.gitst.RepoProperties.PROP_IGNORE;
import static com.aap.gitst.RepoProperties.PROP_MAXCONNECTIONS;
import static com.aap.gitst.RepoProperties.PROP_URL;
import static com.aap.gitst.RepoProperties.PROP_USER_PATTERN;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Andrey Pavlenko
 */
public class Init {

    public static void main(final String[] args) {
        if (args.length == 0) {
            printHelp(System.out);
        } else {
            try {
                final Args a = new Args(args);
                final String host = a.get("-h");
                final int port = a.getInt("-p");
                final String project = a.get("-P");
                final String view = a.get("-V");
                final String branch = a.get("-b", "master");
                final String user = a.get("-u", null);
                final String password = a.get("-pwd", null);
                final String ca = a.get("-ca", null);
                final String ignore = a.get("-i", PROP_DEFAULT_IGNORE);
                final String up = a.get("-up", PROP_DEFAULT_USER_PATTERN);
                final String t = a.get("-t", PROP_DEFAULT_CATHREADS);
                final File dir = new File(a.get("-d", "."));
                final StringBuilder sb = new StringBuilder("st::starteam://");
                final String url;

                if (user != null) {
                    sb.append(user);

                    if (password != null) {
                        sb.append(':').append(password);
                    }

                    sb.append('@');
                }

                sb.append(host).append(':').append(port).append('/')
                        .append(project).append('/').append(view);
                url = sb.toString();
                dir.mkdirs();

                final List<String> initCmd = new ArrayList<>(3);
                initCmd.add("git");
                initCmd.add("init");

                if (a.hasOption("--bare")) {
                    initCmd.add("--bare");
                }

                final int exit = new Exec(dir, initCmd).exec().waitFor();
                if (exit != 0) {
                    throw new ExecutionException("git init failed", exit);
                }

                final Git git = new Git(dir);
                final RepoProperties props = new RepoProperties(git, "origin");
                props.setLocalProperty(PROP_URL, url);
                props.setLocalProperty(PROP_CATHREADS,
                        String.valueOf(Integer.parseInt(t)));
                props.setLocalProperty(PROP_USER_PATTERN, up);
                props.setLocalProperty(PROP_IGNORE, ignore);

                if (ca != null) {
                    props.setLocalProperty(PROP_CA, ca);
                }

                setDefaults(props, git, branch);
            } catch (final IllegalArgumentException ex) {
                System.err.println(ex.getMessage());
                printHelp(System.err);
                System.exit(1);
            } catch (final IOException | InterruptedException ex) {
                System.err.println(ex.getMessage());
                System.exit(1);
            } catch (final ExecutionException ex) {
                System.err.println(ex.getMessage());
                System.exit(ex.getExitCode());
            }
        }
    }

    static void setDefaults(final RepoProperties props, final Git git,
            final String branch) throws IOException, InterruptedException,
            ExecutionException {
        props.setLocalProperty(PROP_FETCH, "+refs/heads/" + branch
                + ":refs/remotes/origin/master");

        if (props.getProperty(PROP_CATHREADS, null) == null) {
            props.setLocalProperty(PROP_CATHREADS, PROP_DEFAULT_CATHREADS);
        }
        if (props.getProperty(PROP_MAXCONNECTIONS, null) == null) {
            props.setLocalProperty(PROP_MAXCONNECTIONS,
                    PROP_DEFAULT_MAXCONNECTIONS);
        }
        if (props.getProperty(PROP_USER_PATTERN, null) == null) {
            props.setLocalProperty(PROP_USER_PATTERN, PROP_DEFAULT_USER_PATTERN);
        }
        if (props.getProperty(PROP_IGNORE, null) == null) {
            props.setLocalProperty(PROP_IGNORE, PROP_DEFAULT_IGNORE);
        }

        props.saveLocalProperties();
        git.exec("config", "core.ignorecase", "false").exec().waitFor();
        git.exec("config", "branch.master.remote", "origin").exec().waitFor();
        git.exec("config", "branch.master.merge", "refs/heads/master").exec()
                .waitFor();
    }

    private static void printHelp(final PrintStream ps) {
        ps.println("Usage: git st init -h <host> -p <port> -P <project> -V <view> "
                + "[-u <user>] [-b <branch>] [-d <directory>] [-ca <CacheAgent>] "
                + "[-t <MaxThreads>] [-up <userpattern>] [-i <ignore>] [--bare]");
    }
}
