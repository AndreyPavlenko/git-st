package com.aap.gitst;

/**
 * @author Andrey Pavlenko
 */
public interface CredentialCallBack {
    public boolean approve(String user, String password);
}
