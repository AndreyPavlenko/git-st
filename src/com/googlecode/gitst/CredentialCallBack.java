package com.googlecode.gitst;

/**
 * @author Andrey Pavlenko
 */
public interface CredentialCallBack {
    public boolean approve(String user, String password);
}
