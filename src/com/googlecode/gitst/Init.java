package com.googlecode.gitst;

import static com.googlecode.gitst.RepoProperties.*;
import static com.googlecode.gitst.RepoProperties.PROP_BRANCH;
import static com.googlecode.gitst.RepoProperties.PROP_CACHE_AGENT_HOST;
import static com.googlecode.gitst.RepoProperties.PROP_CACHE_AGENT_PORT;
import static com.googlecode.gitst.RepoProperties.PROP_DEFAULT_BRANCH;
import static com.googlecode.gitst.RepoProperties.PROP_DEFAULT_USER_NAME_PATTERN;
import static com.googlecode.gitst.RepoProperties.PROP_HOST;
import static com.googlecode.gitst.RepoProperties.PROP_PASSWORD;
import static com.googlecode.gitst.RepoProperties.PROP_PORT;
import static com.googlecode.gitst.RepoProperties.PROP_PROJECT;
import static com.googlecode.gitst.RepoProperties.PROP_USER;
import static com.googlecode.gitst.RepoProperties.PROP_USER_NAME_PATTERN;
import static com.googlecode.gitst.RepoProperties.PROP_VIEW;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;

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
                final String user = a.get("-u", null);
                final String password = a.get("-pwd", null);
                final String branch = a.get("-b", PROP_DEFAULT_BRANCH);
                final String ca = a.get("-ca", null);
                final String t = a.get("-t", null);
                final File dir = new File(a.get("-d", "."));

                if (!dir.exists()) {
                    dir.mkdirs();
                }

                final Git git = new Git(dir);

                if (a.hasOption("--bare")) {
                    git.exec("init", "--bare").exec().waitFor();
                } else {
                    git.exec("init").exec().waitFor();
                }

                final RepoProperties props = new RepoProperties(dir, null);
                props.setRepoProperty(PROP_HOST, host);
                props.setRepoProperty(PROP_PORT, String.valueOf(port));
                props.setRepoProperty(PROP_PROJECT, project);
                props.setRepoProperty(PROP_VIEW, view);
                props.setRepoProperty(PROP_BRANCH, branch);
                props.setRepoProperty(PROP_USER_NAME_PATTERN,
                        PROP_DEFAULT_USER_NAME_PATTERN);

                if (user != null) {
                    props.setRepoProperty(PROP_USER, user);
                }
                if (password != null) {
                    props.setRepoProperty(PROP_PASSWORD, password);
                }
                if (t != null) {
                    props.setRepoProperty(PROP_MAX_THREADS,
                            String.valueOf(Integer.parseInt(t)));
                }
                if (ca != null) {
                    if ("auto".equals(ca)) {
                        props.setRepoProperty(PROP_AUTO_LOCATE_CACHE_AGENT,
                                "true");
                    } else {
                        final int ind = ca.indexOf(':');

                        if (ind == -1) {
                            props.setRepoProperty(PROP_CACHE_AGENT_HOST, ca);
                        } else {
                            props.setRepoProperty(PROP_CACHE_AGENT_HOST,
                                    ca.substring(0, ind));
                            props.setRepoProperty(PROP_CACHE_AGENT_PORT,
                                    ca.substring(ind + 1));
                        }
                    }
                }

                props.saveRepoProperties();
                props.saveRepoUserMapings();
            } catch (final IllegalArgumentException ex) {
                System.err.println(ex.getMessage());
                printHelp(System.err);
            } catch (final InterruptedException ex) {
                ex.printStackTrace();
            } catch (final IOException ex) {
                System.err.println(ex.getMessage());
            }
        }
    }

    private static void printHelp(final PrintStream ps) {
        ps.println("Usage: git st init -h <host> -p <port> -P <project> -V <view> "
                + "[-u <user>] [-b <branch>] [-d <directory>] [-ca <CacheAgent>] "
                + "[-t <MaxThreads>] [--bare]");
    }
}
